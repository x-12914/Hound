from datetime import datetime, timezone
from enum import Enum

from sqlmodel import Field, Relationship, SQLModel


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


class Role(str, Enum):
    owner = "owner"
    admin = "admin"


class AlertStatus(str, Enum):
    active = "active"
    acknowledged = "acknowledged"
    resolved = "resolved"
    cancelled = "cancelled"


class User(SQLModel, table=True):
    id: int | None = Field(default=None, primary_key=True)
    email: str = Field(index=True, unique=True)
    hashed_password: str
    full_name: str = ""
    role: Role = Field(default=Role.owner)
    is_active: bool = True
    created_at: datetime = Field(default_factory=utcnow)

    devices: list["Device"] = Relationship(back_populates="owner")
    emergency_contacts: list["EmergencyContact"] = Relationship(back_populates="user")


class EmergencyContact(SQLModel, table=True):
    id: int | None = Field(default=None, primary_key=True)
    user_id: int = Field(foreign_key="user.id", index=True)
    name: str
    phone: str = ""
    relation: str = ""
    position: int = 0  # 1 or 2, preserves display order
    created_at: datetime = Field(default_factory=utcnow)

    user: User | None = Relationship(back_populates="emergency_contacts")


class Device(SQLModel, table=True):
    id: int | None = Field(default=None, primary_key=True)
    owner_id: int = Field(foreign_key="user.id", index=True)
    name: str = "My phone"
    platform: str = "android"
    # Stable id the app generates (e.g. a UUID) so re-installs/relogins map to one device.
    install_id: str = Field(index=True)
    push_token: str | None = None  # FCM token, for future server->app pushes
    last_seen: datetime | None = None
    created_at: datetime = Field(default_factory=utcnow)

    owner: User | None = Relationship(back_populates="devices")
    alerts: list["Alert"] = Relationship(back_populates="device")


class Alert(SQLModel, table=True):
    id: int | None = Field(default=None, primary_key=True)
    device_id: int = Field(foreign_key="device.id", index=True)
    owner_id: int = Field(foreign_key="user.id", index=True)
    status: AlertStatus = Field(default=AlertStatus.active, index=True)
    triggered_at: datetime = Field(default_factory=utcnow, index=True)
    acknowledged_at: datetime | None = None
    acknowledged_by: int | None = Field(default=None, foreign_key="user.id")
    resolved_at: datetime | None = None
    note: str = ""

    device: Device | None = Relationship(back_populates="alerts")
    locations: list["LocationPoint"] = Relationship(back_populates="alert")
    audio_clips: list["AudioClip"] = Relationship(back_populates="alert")


class LocationPoint(SQLModel, table=True):
    id: int | None = Field(default=None, primary_key=True)
    alert_id: int = Field(foreign_key="alert.id", index=True)
    lat: float
    lng: float
    accuracy_m: float | None = None
    speed_mps: float | None = None
    recorded_at: datetime = Field(default_factory=utcnow, index=True)

    alert: "Alert" = Relationship(back_populates="locations")


class AudioClip(SQLModel, table=True):
    id: int | None = Field(default=None, primary_key=True)
    alert_id: int = Field(foreign_key="alert.id", index=True)
    filename: str
    content_type: str = "audio/aac"
    duration_s: float | None = None
    size_bytes: int | None = None
    uploaded_at: datetime = Field(default_factory=utcnow)

    alert: Alert | None = Relationship(back_populates="audio_clips")
