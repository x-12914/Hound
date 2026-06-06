# Hound Android app

Kotlin app that detects rapid power-button presses and fires a silent SOS with live
location + audio to your Hound backend.

## Build & run
1. Install **Android Studio** (Hedgehog or newer) with an SDK for **API 34**.
2. **Open** this `android/` folder (not the repo root) as a project. Android Studio
   generates the Gradle wrapper and downloads dependencies automatically.
3. Plug in a phone (USB debugging on) **or** start an emulator. A *real device* is
   strongly recommended — the power-button/screen-toggle trigger and GPS behave more
   realistically than on an emulator.
4. Press **Run ▶**.

> Why no `./gradlew` here? The binary Gradle wrapper jar isn't checked in. Android
> Studio creates it on first open. If you prefer CLI, run
> `gradle wrapper --gradle-version 8.9` once (needs Gradle + JDK 17 installed), then
> `./gradlew assembleDebug`. AGP 8.5 needs **JDK 17** specifically.

## First-run flow
1. **Server URL** — emulator: `http://10.0.2.2:8000`; real phone on your Wi-Fi:
   `http://<your-PC-LAN-IP>:8000` (and uncomment that IP in
   `res/xml/network_security_config.xml`); production: `https://your-domain`.
2. Create an account (or sign in).
3. **Onboarding** grants: location + microphone + notifications, then *background
   location* ("Allow all the time"), then the *battery-optimization* exemption. All
   three are needed for the guardian to survive a locked screen.
4. The **Guardian** toggle starts the foreground service. Status shows "Protected".
5. **Send a test SOS** to confirm the whole pipeline lights up the dashboard.

## How triggering works
The foreground service (`service/SosForegroundService.kt`) registers a *dynamic*
receiver for `SCREEN_ON`/`SCREEN_OFF` — the only power-press signal available without
root. It counts toggles in a sliding window; **3 within 2000 ms** (configurable in
Settings) fires `SosController.trigger()`, which:
1. grabs a location fix and `POST`s `/api/alerts`,
2. streams further fixes to `/api/alerts/{id}/locations` every N seconds,
3. records rolling AAC clips and uploads them to `/api/alerts/{id}/audio`,
until you tap **"I'm safe — cancel"** (notification action or in-app button).

## Tuning (Settings screen)
- Presses to trigger (2–8) and press window (ms)
- Location update interval (sec)
- Audio clip length (sec) and an audio on/off switch

## Project map
```
service/SosForegroundService.kt  persistent guardian + power-press detector
service/BootReceiver.kt          restart guardian after reboot
sos/SosController.kt             alert lifecycle: create → stream loc → audio loop
location/LocationStreamer.kt     FusedLocationProvider wrapper
audio/AudioRecorder.kt           MediaRecorder AAC clips
data/Api.kt                      OkHttp client for the backend
data/Prefs.kt                    token, server URL, install id, settings
ui/                              Login, Onboarding, Main (status), Settings
```

## Notes / gotchas
- **Cleartext**: only allowed to the dev hosts in `network_security_config.xml`.
  Production must be HTTPS.
- **OEM battery killers** (Xiaomi/Oppo/Samsung): besides the battery exemption, some
  skins need the app pinned in "recents" / set to "no restrictions" to stay alive.
- **Accidental triggers**: a built-in 8-second debounce prevents one panic burst from
  creating multiple alerts. If you get false positives, raise the press count or
  shorten the window in Settings.
