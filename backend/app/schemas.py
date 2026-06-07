from datetime import datetime

from pydantic import BaseModel, EmailStr

from .models import AlertStatus, Role

# ---- Auth ----


class EmergencyContactIn(BaseModel):
    name: str
    phone: str = ""
    relation: str = ""


class EmergencyContactOut(BaseModel):
    name: str
    phone: str
    relation: str

    class Config:
        from_attributes = True


class RegisterRequest(BaseModel):
    email: EmailStr
    password: str
    full_name: str = ""
    contacts: list[EmergencyContactIn] = []


class LoginRequest(BaseModel):
    email: EmailStr
    password: str


class TokenResponse(BaseModel):
    access_token: str
    token_type: str = "bearer"
    user_id: int
    role: Role


class UserOut(BaseModel):
    id: int
    email: EmailStr
    full_name: str
    role: Role

    class Config:
        from_attributes = True


# ---- Devices ----


class DeviceRegisterRequest(BaseModel):
    install_id: str
    name: str = "My phone"
    platform: str = "android"
    push_token: str | None = None


class DeviceOut(BaseModel):
    id: int
    owner_id: int
    name: str
    platform: str
    install_id: str
    last_seen: datetime | None

    class Config:
        from_attributes = True


# ---- Alerts ----


class LocationIn(BaseModel):
    lat: float
    lng: float
    accuracy_m: float | None = None
    speed_mps: float | None = None
    recorded_at: datetime | None = None


class AlertCreateRequest(BaseModel):
    device_id: int
    # optional first location captured at trigger time
    location: LocationIn | None = None
    note: str = ""


class LocationOut(BaseModel):
    id: int
    lat: float
    lng: float
    accuracy_m: float | None
    speed_mps: float | None
    recorded_at: datetime

    class Config:
        from_attributes = True


class AudioOut(BaseModel):
    id: int
    filename: str
    content_type: str
    duration_s: float | None
    uploaded_at: datetime

    class Config:
        from_attributes = True


class AlertOut(BaseModel):
    id: int
    device_id: int
    owner_id: int
    status: AlertStatus
    triggered_at: datetime
    acknowledged_at: datetime | None
    resolved_at: datetime | None
    note: str

    class Config:
        from_attributes = True


class AlertDetailOut(AlertOut):
    locations: list[LocationOut] = []
    audio_clips: list[AudioOut] = []
    contacts: list[EmergencyContactOut] = []
    device_name: str = ""
    owner_email: str = ""


class StatusUpdateRequest(BaseModel):
    status: AlertStatus
    note: str | None = None
