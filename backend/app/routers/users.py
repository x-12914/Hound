from fastapi import APIRouter, Depends
from sqlmodel import Session, select

from ..database import get_session
from ..deps import get_current_user
from ..models import Device, EmergencyContact, Role, User
from ..schemas import EmergencyContactOut, UserWithContactsOut

router = APIRouter(prefix="/api/users", tags=["users"])


@router.get("", response_model=list[UserWithContactsOut])
def list_users(
    user: User = Depends(get_current_user),
    session: Session = Depends(get_session),
):
    """Protected people (owners) and their emergency contacts.

    Admins see everyone; an owner sees only themselves.
    """
    query = select(User).where(User.role == Role.owner)
    if user.role != Role.admin:
        query = query.where(User.id == user.id)
    owners = session.exec(query.order_by(User.created_at.desc())).all()

    out: list[UserWithContactsOut] = []
    for u in owners:
        contacts = session.exec(
            select(EmergencyContact)
            .where(EmergencyContact.user_id == u.id)
            .order_by(EmergencyContact.position)
        ).all()
        device_count = len(
            session.exec(select(Device).where(Device.owner_id == u.id)).all()
        )
        out.append(
            UserWithContactsOut(
                id=u.id,
                email=u.email,
                full_name=u.full_name,
                role=u.role,
                created_at=u.created_at,
                device_count=device_count,
                contacts=[EmergencyContactOut.model_validate(c) for c in contacts],
            )
        )
    return out
