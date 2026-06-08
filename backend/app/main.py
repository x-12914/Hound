import os
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from sqlmodel import Session, select

from .config import settings
from .database import engine, init_db
from .models import Role, User
from .routers import alerts, auth, contacts, devices, realtime
from .security import hash_password


def _bootstrap_admin() -> None:
    with Session(engine) as session:
        existing = session.exec(
            select(User).where(User.email == settings.admin_email)
        ).first()
        if existing is None:
            admin = User(
                email=settings.admin_email,
                hashed_password=hash_password(settings.admin_password),
                full_name="Administrator",
                role=Role.admin,
            )
            session.add(admin)
            session.commit()
            print(f"[hound] created bootstrap admin: {settings.admin_email}")


@asynccontextmanager
async def lifespan(app: FastAPI):
    init_db()
    _bootstrap_admin()
    os.makedirs(settings.media_dir, exist_ok=True)
    yield


app = FastAPI(title="Hound Emergency Response API", version="0.1.0", lifespan=lifespan)

app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origin_list,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(auth.router)
app.include_router(devices.router)
app.include_router(alerts.router)
app.include_router(contacts.router)
app.include_router(realtime.router)


@app.get("/api/health")
def health():
    return {"status": "ok"}


# Serve the static dashboard if the folder exists (mounted last so /api wins).
_dashboard_dir = os.path.join(os.path.dirname(__file__), "..", "..", "dashboard")
_dashboard_dir = os.path.abspath(_dashboard_dir)
if os.path.isdir(_dashboard_dir):
    app.mount("/", StaticFiles(directory=_dashboard_dir, html=True), name="dashboard")
