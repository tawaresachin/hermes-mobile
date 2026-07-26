# Hermes Mobile

Native Android companion for the Hermes AI agent — Kotlin + Jetpack Compose.

**Stack:** Material 3, Hilt DI, Room DB, OkHttp SSE streaming, Coroutines

| Tab | Features |
|---|---|
| 💬 Chat | Streaming Markdown, code syntax highlighting, voice recording, tool call cards, typing indicator |
| 📋 Sessions | Search, swipe delete, pull-to-refresh |
| ⚙️ Settings | Server config, connection test, theme, TTS/voice prefs |
| 🏠 Home | Glassmorphism dashboard, quick actions, recent sessions |

## Build

```bash
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

Requires API 34+. Connect to a running Hermes API instance.
