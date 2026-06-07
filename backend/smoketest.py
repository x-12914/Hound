"""End-to-end smoke test against a running server on 127.0.0.1:8011.

Exercises: health, owner register, device register, alert create with location,
live location append, audio upload, admin sees everything, WS receives events.
"""

import asyncio
import io
import json
import time
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:8011"


def req(method, path, token=None, json_body=None, form=None):
    url = BASE + path
    headers = {}
    data = None
    if json_body is not None:
        data = json.dumps(json_body).encode()
        headers["Content-Type"] = "application/json"
    if form is not None:
        data = form.encode()
        headers["Content-Type"] = "application/x-www-form-urlencoded"
    if token:
        headers["Authorization"] = f"Bearer {token}"
    r = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(r, timeout=10) as resp:
            return resp.status, json.loads(resp.read().decode() or "{}")
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode() or "{}")


def upload_audio(path, token):
    boundary = "----houndtest"
    audio_bytes = b"\x00\x01FAKEAAC" * 16
    body = io.BytesIO()
    body.write(f"--{boundary}\r\n".encode())
    body.write(
        b'Content-Disposition: form-data; name="file"; filename="clip.aac"\r\n'
    )
    body.write(b"Content-Type: audio/aac\r\n\r\n")
    body.write(audio_bytes)
    body.write(f"\r\n--{boundary}\r\n".encode())
    body.write(b'Content-Disposition: form-data; name="duration_s"\r\n\r\n')
    body.write(b"4.2")
    body.write(f"\r\n--{boundary}--\r\n".encode())
    data = body.getvalue()
    r = urllib.request.Request(
        BASE + path,
        data=data,
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": f"multipart/form-data; boundary={boundary}",
        },
        method="POST",
    )
    with urllib.request.urlopen(r, timeout=10) as resp:
        return resp.status, json.loads(resp.read().decode())


results = []


def check(name, cond, extra=""):
    results.append((name, cond, extra))
    print(("PASS" if cond else "FAIL"), name, extra)


# wait for server
for _ in range(40):
    try:
        s, _ = req("GET", "/api/health")
        if s == 200:
            break
    except Exception:
        time.sleep(0.25)

s, b = req("GET", "/api/health")
check("health", s == 200 and b.get("status") == "ok")

# owner register
s, b = req(
    "POST",
    "/api/auth/register",
    json_body={
        "email": "owner1@test.com",
        "password": "pw12345",
        "full_name": "Owner One",
        "contacts": [
            {"name": "Contact A", "phone": "+10000000001", "relation": "Mother"},
            {"name": "Contact B", "phone": "+10000000002", "relation": "Brother"},
        ],
    },
)
check("owner register", s == 200 and "access_token" in b, str(s))
owner_token = b.get("access_token")
owner_id = b.get("user_id")

# device register
s, b = req(
    "POST",
    "/api/devices",
    token=owner_token,
    json_body={"install_id": "test-install-001", "name": "Pixel Test"},
)
check("device register", s == 200 and b.get("install_id") == "test-install-001", str(s))
device_id = b.get("id")

# admin login (bootstrap admin from .env: admin@example.com / change-me)
s, b = req(
    "POST",
    "/api/auth/login-json",
    json_body={"email": "admin@example.com", "password": "change-me"},
)
check("admin login", s == 200 and b.get("role") == "admin", str(s))
admin_token = b.get("access_token")


async def ws_listener(events_box):
    import websockets  # type: ignore

    uri = f"ws://127.0.0.1:8011/ws?token={admin_token}"
    async with websockets.connect(uri) as ws:
        events_box["ready"] = True
        try:
            while True:
                msg = await asyncio.wait_for(ws.recv(), timeout=8)
                events_box["events"].append(json.loads(msg))
        except (asyncio.TimeoutError, Exception):
            return


async def main():
    events_box = {"events": [], "ready": False}
    try:
        listener = asyncio.create_task(ws_listener(events_box))
        for _ in range(40):
            if events_box["ready"]:
                break
            await asyncio.sleep(0.1)
        ws_ok = events_box["ready"]
    except Exception as e:
        ws_ok = False
        listener = None
        print("WS connect failed (websockets lib?):", e)

    check("ws admin connect", ws_ok)

    # create alert with location
    s, b = req(
        "POST",
        "/api/alerts",
        token=owner_token,
        json_body={
            "device_id": device_id,
            "location": {"lat": 33.5731, "lng": -7.5898, "accuracy_m": 12.0},
            "note": "triple press",
        },
    )
    check("alert create", s == 200 and b.get("status") == "active", str(s))
    alert_id = b.get("id")

    # append a live location
    s, b = req(
        "POST",
        f"/api/alerts/{alert_id}/locations",
        token=owner_token,
        json_body={"lat": 33.5740, "lng": -7.5905, "accuracy_m": 8.0, "speed_mps": 1.4},
    )
    check("location append", s == 200 and b.get("lat") == 33.5740, str(s))

    # upload audio
    s, b = upload_audio(f"/api/alerts/{alert_id}/audio", owner_token)
    check("audio upload", s == 200 and b.get("id") is not None, str(s))
    clip_id = b.get("id")

    # admin lists all alerts and sees detail
    s, b = req("GET", "/api/alerts", token=admin_token)
    found = any(a.get("id") == alert_id for a in b) if isinstance(b, list) else False
    detail = next((a for a in b if a.get("id") == alert_id), {}) if isinstance(b, list) else {}
    check("admin list sees alert", s == 200 and found, str(s))
    check(
        "alert detail has 2 locations + 1 audio",
        len(detail.get("locations", [])) == 2 and len(detail.get("audio_clips", [])) == 1,
        f"locs={len(detail.get('locations', []))} audio={len(detail.get('audio_clips', []))}",
    )

    # owner cannot see other owners; make a second owner and ensure isolation
    s, b = req(
        "POST",
        "/api/auth/register",
        json_body={
            "email": "owner2@test.com",
            "password": "pw12345",
            "contacts": [
                {"name": "C1", "phone": "+1", "relation": "Friend"},
                {"name": "C2", "phone": "+2", "relation": "Friend"},
            ],
        },
    )
    other_token = b.get("access_token")
    s, b = req("GET", "/api/alerts", token=other_token)
    check("owner isolation", s == 200 and isinstance(b, list) and len(b) == 0, str(b))

    # acknowledge from dashboard
    s, b = req(
        "POST",
        f"/api/alerts/{alert_id}/status",
        token=admin_token,
        json_body={"status": "acknowledged"},
    )
    check("acknowledge", s == 200 and b.get("status") == "acknowledged", str(s))

    # download audio file
    r = urllib.request.Request(
        BASE + f"/api/alerts/{alert_id}/audio/{clip_id}/file",
        headers={"Authorization": f"Bearer {admin_token}"},
    )
    with urllib.request.urlopen(r, timeout=10) as resp:
        audio_data = resp.read()
    check("audio download", len(audio_data) > 0, f"{len(audio_data)} bytes")

    # give WS a moment to flush
    await asyncio.sleep(1.0)
    if listener:
        listener.cancel()

    types = [e.get("type") for e in events_box["events"]]
    check("ws got alert.new", "alert.new" in types, str(types))
    check("ws got alert.location", "alert.location" in types, str(types))
    check("ws got alert.audio", "alert.audio" in types, str(types))
    check("ws got alert.status", "alert.status" in types, str(types))

    passed = sum(1 for _, c, _ in results if c)
    print(f"\n=== {passed}/{len(results)} checks passed ===")
    return passed == len(results)


ok = asyncio.run(main())
raise SystemExit(0 if ok else 1)
