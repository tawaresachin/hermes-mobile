# Hermes Mobile – Android companion for Hermes Agent ☤

[![License: MIT](https://img.shields.io/badge/License-MIT-green?style=for-the-badge)](https://github.com/tawaresachin/hermes-mobile/blob/main/LICENSE)
[![Version](https://img.shields.io/badge/Version-0.0.46-blue?style=for-the-badge)](https://github.com/tawaresachin/hermes-mobile/releases)
[![Docs](https://img.shields.io/badge/Docs-Hermes--Agent-FFD700?style=for-the-badge)](https://hermes-agent.nousresearch.com/docs/)
[![CI](https://img.shields.io/github/actions/workflow/status/tawaresachin/hermes-mobile/ci.yml?branch=main&style=for-the-badge)](https://github.com/tawaresachin/hermes-mobile/actions)

**What it does** – Native Android client that talks to a Hermes Agent gateway (port 8642) and provides chat, voice and file attachment features.

| Feature | Description |
|---|---|
| Chat | Streaming markdown, code highlighting, voice recording, tool‑call cards. |
| Sessions | Search, swipe‑to‑delete, pull‑to‑refresh. |
| Settings | Server URL, connection test, theme, TTS/voice prefs. |
| Home | Recent sessions view, tappable connection line with live latency. |

## Build & install

1. Install Android SDK and Java 17 (if not already present).  
2. From the repository root run:

```bash
./gradlew assembleDebug
```

3. The APK is created at `app/build/outputs/apk/debug/app-debug.apk`.  
4. Transfer the APK to your device (adb, USB, email) and install it.

## Run the app

1. Open the app → Settings → **Server config**.  
2. Enter the URL shown by `hermes-mobile-plugin qr` (e.g. `http://100.87.9.77:8642`).  
3. The app connects automatically; you can start a chat immediately.

## Development (optional)

```bash
git clone https://github.com/tawaresachin/hermes-mobile.git
cd hermes-mobile
./gradlew assembleDebug   # build
./gradlew test           # run unit tests
```

## License

MIT – see the LICENSE file.
