from fastapi import APIRouter, Depends
from sqlmodel import Session, select

from ..database import get_session
from ..deps import get_current_user
from ..models import Device, User, utcnow
from ..schemas import DeviceOut, DeviceRegisterRequest

router = APIRouter(prefix="/api/devices", tags=["devices"])


@router.post("", response_model=DeviceOut)
def register_device(
    body: DeviceRegisterRequest,
    user: User = Depends(get_current_user),
    session: Session = Depends(get_session),
):
    """Idempotent: same install_id for the same owner updates the existing device."""
    device = session.exec(
        select(Device).where(
            Device.owner_id == user.id, Device.install_id == body.install_id
        )
    ).first()
    if device is None:
        device = Device(owner_id=user.id, install_id=body.install_id)
    device.name = body.name
    device.platform = body.platform
    device.push_token = body.push_token
    device.last_seen = utcnow()
    session.add(device)
    session.commit()
    session.refresh(device)
    return device


@router.get("", response_model=list[DeviceOut])
def list_my_devices(
    user: User = Depends(get_current_user),
    session: Session = Depends(get_session),
):
    return session.exec(select(Device).where(Device.owner_id == user.id)).all()
