import os
import uuid
from datetime import datetime

from fastapi import (
    APIRouter,
    Depends,
    File,
    Form,
    HTTPException,
    UploadFile,
    status,
)
from fastapi.responses import FileResponse
from sqlmodel import Session, select

from ..config import settings
from ..database import get_session
from ..deps import get_current_user
from ..models import (
    Alert,
    AlertStatus,
    AudioClip,
    Device,
    EmergencyContact,
    LocationPoint,
    Role,
    User,
    utcnow,
)
from ..schemas import (
    AlertCreateRequest,
    AlertDetailOut,
    AlertOut,
    AudioOut,
    EmergencyContactOut,
    LocationIn,
    LocationOut,
    StatusUpdateRequest,
)
from ..ws import manager

router = APIRouter(prefix="/api/alerts", tags=["alerts"])


# ---- helpers ----


def _get_owned_alert(alert_id: int, user: User, session: Session) -> Alert:
    alert = session.get(Alert, alert_id)
    if alert is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Alert not found")
    if user.role != Role.admin and alert.owner_id != user.id:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "Not your alert")
    return alert


def _location_payload(loc: LocationPoint) -> dict:
    return {
        "id": loc.id,
        "lat": loc.lat,
        "lng": loc.lng,
        "accuracy_m": loc.accuracy_m,
        "speed_mps": loc.speed_mps,
        "recorded_at": loc.recorded_at.isoformat(),
    }


def _contacts_for(user_id: int, session: Session) -> list[EmergencyContact]:
    return session.exec(
        select(EmergencyContact)
        .where(EmergencyContact.user_id == user_id)
        .order_by(EmergencyContact.position)
    ).all()


def _contact_payload(c: EmergencyContact) -> dict:
    return {"name": c.name, "phone": c.phone, "relation": c.relation}


def _alert_summary(
    alert: Alert,
    device: Device | None,
    owner: User | None,
    contacts: list[EmergencyContact] | None = None,
) -> dict:
    return {
        "id": alert.id,
        "device_id": alert.device_id,
        "owner_id": alert.owner_id,
        "status": alert.status.value,
        "triggered_at": alert.triggered_at.isoformat(),
        "note": alert.note,
        "device_name": device.name if device else "",
        "owner_email": owner.email if owner else "",
        "contacts": [_contact_payload(c) for c in (contacts or [])],
    }


# ---- create / update from the mobile app ----


@router.post("", response_model=AlertOut)
async def create_alert(
    body: AlertCreateRequest,
    user: User = Depends(get_current_user),
    session: Session = Depends(get_session),
):
    device = session.get(Device, body.device_id)
    if device is None or device.owner_id != user.id:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Device not found for this user")

    alert = Alert(device_id=device.id, owner_id=user.id, note=body.note)
    session.add(alert)
    session.commit()
    session.refresh(alert)

    if body.location is not None:
        loc = LocationPoint(
            alert_id=alert.id,
            lat=body.location.lat,
            lng=body.location.lng,
            accuracy_m=body.location.accuracy_m,
            speed_mps=body.location.speed_mps,
            recorded_at=body.location.recorded_at or utcnow(),
        )
        session.add(loc)
        device.last_seen = utcnow()
        session.add(device)
        session.commit()
        session.refresh(loc)

    summary = _alert_summary(
        alert, device, user, _contacts_for(user.id, session)
    )
    if body.location is not None:
        summary["location"] = _location_payload(loc)
    await manager.broadcast({"type": "alert.new", "alert": summary}, owner_id=user.id)
    return alert


@router.post("/{alert_id}/locations", response_model=LocationOut)
async def add_location(
    alert_id: int,
    body: LocationIn,
    user: User = Depends(get_current_user),
    session: Session = Depends(get_session),
):
    alert = _get_owned_alert(alert_id, user, session)
    loc = LocationPoint(
        alert_id=alert.id,
        lat=body.lat,
        lng=body.lng,
        accuracy_m=body.accuracy_m,
        speed_mps=body.speed_mps,
        recorded_at=body.recorded_at or utcnow(),
    )
    session.add(loc)
    session.commit()
    session.refresh(loc)

    await manager.broadcast(
        {
            "type": "alert.location",
            "alert_id": alert.id,
            "location": _location_payload(loc),
        },
        owner_id=alert.owner_id,
    )
    return loc


@router.post("/{alert_id}/audio", response_model=AudioOut)
async def upload_audio(
    alert_id: int,
    file: UploadFile = File(...),
    duration_s: float | None = Form(None),
    user: User = Depends(get_current_user),
    session: Session = Depends(get_session),
):
    alert = _get_owned_alert(alert_id, user, session)

    os.makedirs(settings.media_dir, exist_ok=True)
    ext = os.path.splitext(file.filename or "")[1] or ".aac"
    stored_name = f"alert{alert.id}_{uuid.uuid4().hex}{ext}"
    dest = os.path.join(settings.media_dir, stored_name)

    size = 0
    with open(dest, "wb") as out:
        while chunk := await file.read(1024 * 64):
            out.write(chunk)
            size += len(chunk)

    clip = AudioClip(
        alert_id=alert.id,
        filename=stored_name,
        content_type=file.content_type or "audio/aac",
        duration_s=duration_s,
        size_bytes=size,
    )
    session.add(clip)
    session.commit()
    session.refresh(clip)

    await manager.broadcast(
        {
            "type": "alert.audio",
            "alert_id": alert.id,
            "audio": {
                "id": clip.id,
                "filename": clip.filename,
                "content_type": clip.content_type,
                "duration_s": clip.duration_s,
                "uploaded_at": clip.uploaded_at.isoformat(),
            },
        },
        owner_id=alert.owner_id,
    )
    return clip


# ---- dashboard read / triage ----


@router.get("", response_model=list[AlertDetailOut])
def list_alerts(
    status_filter: AlertStatus | None = None,
    user: User = Depends(get_current_user),
    session: Session = Depends(get_session),
):
    query = select(Alert)
    if user.role != Role.admin:
        query = query.where(Alert.owner_id == user.id)
    if status_filter is not None:
        query = query.where(Alert.status == status_filter)
    query = query.order_by(Alert.triggered_at.desc())
    alerts = session.exec(query).all()

    out: list[AlertDetailOut] = []
    for a in alerts:
        device = session.get(Device, a.device_id)
        owner = session.get(User, a.owner_id)
        detail = AlertDetailOut.model_validate(a)
        detail.locations = [LocationOut.model_validate(l) for l in a.locations]
        detail.audio_clips = [AudioOut.model_validate(c) for c in a.audio_clips]
        detail.contacts = [
            EmergencyContactOut.model_validate(c) for c in _contacts_for(a.owner_id, session)
        ]
        detail.device_name = device.name if device else ""
        detail.owner_email = owner.email if owner else ""
        out.append(detail)
    return out


@router.get("/{alert_id}", response_model=AlertDetailOut)
def get_alert(
    alert_id: int,
    user: User = Depends(get_current_user),
    session: Session = Depends(get_session),
):
    alert = _get_owned_alert(alert_id, user, session)
    device = session.get(Device, alert.device_id)
    owner = session.get(User, alert.owner_id)
    detail = AlertDetailOut.model_validate(alert)
    detail.locations = [LocationOut.model_validate(l) for l in alert.locations]
    detail.audio_clips = [AudioOut.model_validate(c) for c in alert.audio_clips]
    detail.contacts = [
        EmergencyContactOut.model_validate(c) for c in _contacts_for(alert.owner_id, session)
    ]
    detail.device_name = device.name if device else ""
    detail.owner_email = owner.email if owner else ""
    return detail


@router.post("/{alert_id}/status", response_model=AlertOut)
async def update_status(
    alert_id: int,
    body: StatusUpdateRequest,
    user: User = Depends(get_current_user),
    session: Session = Depends(get_session),
):
    alert = _get_owned_alert(alert_id, user, session)
    alert.status = body.status
    if body.note is not None:
        alert.note = body.note
    now = utcnow()
    if body.status == AlertStatus.acknowledged and alert.acknowledged_at is None:
        alert.acknowledged_at = now
        alert.acknowledged_by = user.id
    if body.status in (AlertStatus.resolved, AlertStatus.cancelled):
        alert.resolved_at = now
    session.add(alert)
    session.commit()
    session.refresh(alert)

    await manager.broadcast(
        {"type": "alert.status", "alert_id": alert.id, "status": alert.status.value},
        owner_id=alert.owner_id,
    )
    return alert


@router.get("/{alert_id}/audio/{clip_id}/file")
def download_audio(
    alert_id: int,
    clip_id: int,
    user: User = Depends(get_current_user),
    session: Session = Depends(get_session),
):
    alert = _get_owned_alert(alert_id, user, session)
    clip = session.get(AudioClip, clip_id)
    if clip is None or clip.alert_id != alert.id:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Clip not found")
    path = os.path.join(settings.media_dir, clip.filename)
    if not os.path.exists(path):
        raise HTTPException(status.HTTP_404_NOT_FOUND, "File missing on disk")
    return FileResponse(path, media_type=clip.content_type, filename=clip.filename)
