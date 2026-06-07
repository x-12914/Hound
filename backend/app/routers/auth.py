from fastapi import APIRouter, Depends, HTTPException, status
from fastapi.security import OAuth2PasswordRequestForm
from sqlmodel import Session, select

from ..database import get_session
from ..deps import get_current_user
from ..models import EmergencyContact, Role, User
from ..schemas import LoginRequest, RegisterRequest, TokenResponse, UserOut
from ..security import create_access_token, hash_password, verify_password

router = APIRouter(prefix="/api/auth", tags=["auth"])


def _issue_token(user: User) -> TokenResponse:
    token = create_access_token(user.id)
    return TokenResponse(
        access_token=token, user_id=user.id, role=user.role
    )


@router.post("/register", response_model=TokenResponse)
def register(body: RegisterRequest, session: Session = Depends(get_session)):
    existing = session.exec(select(User).where(User.email == body.email)).first()
    if existing:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT, detail="Email already registered"
        )
    valid_contacts = [c for c in body.contacts if c.name.strip()]
    if len(valid_contacts) < 2:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="Two emergency contacts are required",
        )
    user = User(
        email=body.email,
        hashed_password=hash_password(body.password),
        full_name=body.full_name,
        role=Role.owner,
    )
    session.add(user)
    session.commit()
    session.refresh(user)

    for i, c in enumerate(valid_contacts[:2], start=1):
        session.add(
            EmergencyContact(
                user_id=user.id,
                name=c.name.strip(),
                phone=c.phone.strip(),
                relation=c.relation.strip(),
                position=i,
            )
        )
    session.commit()
    return _issue_token(user)


@router.post("/login", response_model=TokenResponse)
def login(form: OAuth2PasswordRequestForm = Depends(), session: Session = Depends(get_session)):
    """OAuth2 password flow. `username` field carries the email."""
    user = session.exec(select(User).where(User.email == form.username)).first()
    if not user or not verify_password(form.password, user.hashed_password):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Incorrect email or password",
        )
    return _issue_token(user)


@router.post("/login-json", response_model=TokenResponse)
def login_json(body: LoginRequest, session: Session = Depends(get_session)):
    """Convenience JSON login for the mobile app and dashboard fetch()."""
    user = session.exec(select(User).where(User.email == body.email)).first()
    if not user or not verify_password(body.password, user.hashed_password):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Incorrect email or password",
        )
    return _issue_token(user)


@router.get("/me", response_model=UserOut)
def me(user: User = Depends(get_current_user)):
    return user
