# 🛡️ GuardianShield — Parental Control System

A full-stack parental control and device monitoring solution with an Android client and Railway-hosted web dashboard.

## Features

- **📱 Live Screen Mirror** — Real-time 30 FPS screen streaming via WebP
- **🎮 Remote Control** — Tap, swipe, type, and navigate from the dashboard
- **📍 Location Tracking** — Real-time GPS with map trail
- **🚫 App Blocking** — Restrict specific apps with fullscreen overlay
- **🌐 Website Blocking** — DNS-based filtering via local VPN
- **🔊 Remote Alarm** — Max volume alarm for device recovery
- **🔒 Uninstall Protection** — Device Admin prevents removal
- **🔄 Persistence** — Auto-starts on boot, survives background kill
- **📊 Multi-Device** — Monitor multiple devices from one dashboard

## Architecture

```
Android App (Kotlin) ←→ Socket.IO ←→ Node.js Backend (Railway) ←→ Web Dashboard
```

## Quick Start

### 1. Deploy Backend

```bash
cd backend
npm install

# Set your auth token
export AUTH_TOKEN=your-secret-token

# Run locally
node server.js

# Or deploy to Railway
railway login
railway init
railway up
```

Set `AUTH_TOKEN` in Railway environment variables.

### 2. Build Android APK

1. Open `android/` in Android Studio
2. Edit `Config.kt` — set `SERVER_URL` to your Railway URL and `AUTH_TOKEN`
3. Build → Generate APK
4. Install on target device

### 3. Setup on Device

1. Open the app and tap "Start Setup"
2. Grant all requested permissions
3. The device will appear on your dashboard automatically

### 4. Monitor

Open your Railway URL in any browser, enter your auth token, and select a device.

## Tech Stack

| Component | Technology |
|---|---|
| Android App | Kotlin, Socket.IO, MediaProjection, AccessibilityService, VpnService |
| Backend | Node.js, Express, Socket.IO |
| Dashboard | HTML/CSS/JS, Leaflet.js, Canvas API |
| Deployment | Docker, Railway |

## Project Structure

```
├── backend/           # Node.js server (Railway)
│   ├── server.js      # Express + Socket.IO
│   ├── store.js       # JSON data store
│   ├── public/        # Web dashboard
│   ├── Dockerfile
│   └── railway.json
│
└── android/           # Android app (Kotlin)
    └── app/src/main/
        ├── java/com/guardianshield/app/
        │   ├── services/      # Screen capture, location, blocking
        │   ├── receivers/     # Boot, device admin
        │   └── commands/      # Sound player
        └── res/               # XML configs
```

## License

Private use only.
