from fastapi import APIRouter, Depends
from sqlmodel import Session, select

from ..database import get_session
from ..deps import get_current_user
from ..models import EmergencyContact, User
from ..schemas import EmergencyContactOut

router = APIRouter(prefix="/api/contacts", tags=["contacts"])


@router.get("", response_model=list[EmergencyContactOut])
def my_contacts(
    user: User = Depends(get_current_user),
    session: Session = Depends(get_session),
):
    """The current user's emergency contacts — the app caches these locally so
    the SMS fallback can reach them with no internet."""
    return session.exec(
        select(EmergencyContact)
        .where(EmergencyContact.user_id == user.id)
        .order_by(EmergencyContact.position)
    ).all()
