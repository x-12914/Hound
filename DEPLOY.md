# Deploying Hound to a VPS

Target: a fresh Ubuntu 22.04/24.04 VPS with a domain (e.g. `hound.example.com`)
pointed at its IP. Commands assume root or `sudo`.

HTTPS is **required** in production — Android refuses cleartext, browsers refuse
`getUserMedia`/secure WebSockets over plain HTTP, and you're shipping location + audio.

---

## 1. System packages
```bash
apt update && apt install -y python3-venv python3-pip nginx git
# Postgres is optional but recommended for production:
apt install -y postgresql
```

## 2. Get the code + Python deps
```bash
useradd -m -s /bin/bash hound || true
su - hound
git clone <your-repo-url> hound && cd hound/backend
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/pip install "psycopg[binary]"      # only if using Postgres
```

## 3. Configure `.env`
```bash
cp .env.example .env
python3 -c "import secrets; print(secrets.token_hex(32))"   # paste into SECRET_KEY
nano .env
```
Set at minimum:
```
SECRET_KEY=<the 64-char hex you just generated>
ADMIN_EMAIL=you@example.com
ADMIN_PASSWORD=<a strong password>
CORS_ORIGINS=https://hound.example.com
MEDIA_DIR=/home/hound/hound/backend/media
# SQLite (simplest):
DATABASE_URL=sqlite:////home/hound/hound/backend/hound.db
# OR Postgres (see step 4):
# DATABASE_URL=postgresql+psycopg://hound:STRONGPW@localhost:5432/hound
```

## 4. (Optional) Postgres
```bash
sudo -u postgres psql -c "CREATE USER hound WITH PASSWORD 'STRONGPW';"
sudo -u postgres psql -c "CREATE DATABASE hound OWNER hound;"
```
Then use the `postgresql+psycopg://...` `DATABASE_URL` above. Tables are created
automatically on first start.

## 5. systemd service (gunicorn + uvicorn workers)
```bash
.venv/bin/pip install gunicorn
exit   # back to root
```
Create `/etc/systemd/system/hound.service`:
```ini
[Unit]
Description=Hound Emergency API
After=network.target

[Service]
User=hound
WorkingDirectory=/home/hound/hound/backend
EnvironmentFile=/home/hound/hound/backend/.env
# 1 worker keeps the in-process WebSocket hub coherent. To scale to multiple
# workers, move realtime fan-out to Redis pub/sub first (see "Scaling" below).
ExecStart=/home/hound/hound/backend/.venv/bin/gunicorn app.main:app \
    -k uvicorn.workers.UvicornWorker -w 1 -b 127.0.0.1:8000 --timeout 120
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
```
```bash
systemctl daemon-reload && systemctl enable --now hound
systemctl status hound          # confirm it's running
```

## 6. nginx reverse proxy (with WebSocket upgrade)
Create `/etc/nginx/sites-available/hound`:
```nginx
server {
    listen 80;
    server_name hound.example.com;

    client_max_body_size 25m;          # audio clip uploads

    location / {
        proxy_pass http://127.0.0.1:8000;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    # WebSocket endpoint for the dashboard realtime feed
    location /ws {
        proxy_pass http://127.0.0.1:8000/ws;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_read_timeout 3600s;       # keep long-lived sockets open
    }
}
```
```bash
ln -s /etc/nginx/sites-available/hound /etc/nginx/sites-enabled/
nginx -t && systemctl reload nginx
```

## 7. HTTPS with Let's Encrypt
```bash
apt install -y certbot python3-certbot-nginx
certbot --nginx -d hound.example.com
```
certbot rewrites the nginx config to listen on 443 and auto-renews. Done — the API
and dashboard are now at `https://hound.example.com`, and the app should use that as
its Server URL.

## 8. Point the app at production
In the Android app's login screen, set **Server URL** = `https://hound.example.com`.
No cleartext exception needed because it's HTTPS.

---

## Updating
```bash
su - hound && cd hound && git pull
cd backend && .venv/bin/pip install -r requirements.txt
exit && systemctl restart hound
```

## Backups
- SQLite: back up `backend/hound.db` + the `backend/media/` folder.
- Postgres: `pg_dump hound > hound_$(date +%F).sql` (cron it) + back up `media/`.

## Data retention (important — this is sensitive PII)
Location traces and audio recordings are highly sensitive. Decide a retention policy
and enforce it. A simple cron that deletes resolved alerts' media after N days:
```bash
# delete audio files older than 30 days
find /home/hound/hound/backend/media -type f -mtime +30 -delete
```
For DB rows, add a scheduled task to drop `LocationPoint`/`AudioClip` for alerts
resolved more than N days ago. Tell your users what you keep and for how long.

## Scaling past one worker (later)
The realtime hub (`app/ws.py`) holds WebSocket connections **in process**, so it must
run as a single worker today. To scale horizontally, publish alert events to Redis
pub/sub and have each worker fan out to its own sockets. Not needed until you have many
concurrent operators — one worker handles a large number of devices fine.
