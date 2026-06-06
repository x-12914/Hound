# Hound — Emergency Response / Anti-Kidnapping System

Press the power button several times fast → a silent SOS fires to a live dashboard
with your **location streaming in real time** and **audio from the phone's mic**.

Three parts, all in this repo:

| Part | Folder | Stack | Status |
|------|--------|-------|--------|
| **Backend API + realtime** | `backend/` | Python · FastAPI · SQLite→Postgres · WebSockets | ✅ built & tested (17/17 e2e checks) |
| **Operator dashboard** | `dashboard/` | Vanilla JS · Leaflet map (free, no API key) | ✅ built, served by the backend |
| **Android app** | `android/` | Kotlin · foreground service · FusedLocation · MediaRecorder | ✅ built — open in Android Studio to compile |

```
 Android app (Kotlin)                 VPS (FastAPI)               Dashboard (browser)
 ┌──────────────────┐          ┌───────────────────────┐       ┌────────────────────┐
 │ Foreground svc   │  HTTPS   │  /api/auth  /devices   │  WS   │ Live Leaflet map   │
 │  • power-press   │ ───────► │  /alerts  /locations   │ ────► │ alert feed + siren │
 │    detector      │   POST   │  /audio                │ push  │ audio playback     │
 │  • GPS stream    │          │  WebSocket hub  ───────┼──────►│ acknowledge/resolve│
 │  • mic capture   │          │  SQLite / Postgres     │       └────────────────────┘
 └──────────────────┘          └───────────────────────┘
```

## How the "press power 3× fast" trigger works

Android does **not** let an app read the hardware power button directly (it's reserved
by the OS). The proven workaround every panic app uses: each power press toggles the
screen, firing a `SCREEN_ON` / `SCREEN_OFF` system broadcast. A persistent **foreground
service** counts those toggles and fires an SOS when it sees *N within a short window*
(default **3 presses / 2 seconds**, both configurable in Settings). No root needed.

Caveats baked into the design:
- Requires a persistent foreground-service notification (Android kills background listeners).
- Needs **"Allow location all the time"** + a **battery-optimization exemption** to stay alive when locked — the onboarding screen walks the user through both.
- Some OEM skins ship their own 5-press Emergency SOS; we use 3 to avoid collision.

## Roles
- **owner** — a normal user. Sees only their own device(s) and alerts.
- **admin** — you (the operator). Sees **every** owner's alerts on the dashboard.

A bootstrap admin is created on first boot from `ADMIN_EMAIL` / `ADMIN_PASSWORD` in `.env`.

---

## Quick start (local dev)

### 1. Backend + dashboard
```powershell
cd backend
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
Copy-Item .env.example .env          # then edit SECRET_KEY + ADMIN_PASSWORD
.\.venv\Scripts\python.exe -m uvicorn app.main:app --host 0.0.0.0 --port 8000
```
- Dashboard:  http://localhost:8000/   (log in with the admin creds from `.env`)
- API docs:   http://localhost:8000/docs
- Health:     http://localhost:8000/api/health

> On macOS/Linux use `.venv/bin/python` instead of `.\.venv\Scripts\python.exe`.

### 2. Android app
Open the **`android/`** folder in **Android Studio** (Hedgehog or newer). It will
generate the Gradle wrapper, download the SDK, and let you Run on a device/emulator.

In the app's first screen set **Server URL**:
- Emulator → `http://10.0.2.2:8000` (reaches your PC's localhost)
- Real phone on same Wi-Fi → `http://<your-PC-LAN-IP>:8000`
- Production → `https://your-domain`

Then: create an account → grant permissions → toggle the Guardian on → hit **Send a
test SOS** and watch it appear live on the dashboard.

> ⚠️ The app records audio and uploads it `cleartext` only against `http://` dev
> servers. Android blocks cleartext by default on `targetSdk 34`; for LAN testing add a
> `network_security_config` (see `android/README.md`). In production you'll use HTTPS,
> which needs no exception.

---

## Deploying to your VPS
See **[DEPLOY.md](DEPLOY.md)** — systemd service, nginx reverse proxy, Let's Encrypt
HTTPS (required for the WebSocket + mic in production), and switching SQLite → Postgres.

## Security notes (read before going live)
- Change `SECRET_KEY` and the admin password. Never commit `.env`.
- Put the API behind **HTTPS** — mobile sends location/audio + a bearer token.
- Set `CORS_ORIGINS` to your dashboard origin (not `*`) in production.
- Audio/location are sensitive personal data — see DEPLOY.md for a retention note.

## What's verified vs. what needs a device
- **Backend**: fully tested end-to-end (`backend/smoketest.py`, 17/17 pass) — auth,
  device registration, alert creation, live location, audio up/download, owner↔admin
  isolation, and all four realtime WebSocket events.
- **Dashboard**: serves correctly; map + live updates need a browser to eyeball.
- **Android**: written and self-reviewed; compile + on-device testing happens in
  Android Studio (no Android toolchain on this machine).
