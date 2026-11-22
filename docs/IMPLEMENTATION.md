# Hermes Mobile — Implementation Reference

Working notes for future changes. Verified against source at `app/build.gradle.kts` version `0.1.23` / `versionCode 123`.

Where a claim comes from the README rather than code, it is marked **(README)**.

---

## 1. What this app is

A native Android client that talks **directly** to a Hermes Agent gateway on the user's own machine
(`api_server`, default port **8642**). No cloud relay, no app accounts. Pairing is a QR scan that
delivers server URL + API key, then a connection test.

Every "smart" behaviour lives on the server. The app is a thin, resilient view + transport.

---

## 2. Stack

| Concern | Choice |
|---|---|
| Language / UI | Kotlin, Jetpack Compose, Material 3 |
| SDK | compileSdk 34, minSdk 26, targetSdk 34, JDK 17 |
| DI | Hilt (`@HiltAndroidApp`, `@HiltViewModel`) |
| Persistence | Room (sessions + messages), DataStore prefs, EncryptedSharedPreferences for secrets |
| HTTP | OkHttp **directly** — Retrofit/Gson were removed (zero references) |
| Streaming | `okhttp-sse` |
| Images | Coil, wired with the same `AuthInterceptor` |
| QR | `zxing-android-embedded` |
| SSH | `sshj` (built-in terminal) |
| Tests | JUnit4, MockK, coroutines-test; `org.json` real impl on the unit-test classpath |

Architecture: **single Activity, MVVM**, Compose Navigation.

---

## 3. Package map

```
com.hermes.mobile
├── HermesApp.kt              Application: Hilt, Coil loader, crash handler, run recovery
├── MainActivity.kt           single Activity host
├── Navigation.kt             Compose nav graph
├── AppModule.kt              Hilt providers
├── DiagLog.kt                ring-buffer diagnostic log (uploaded to gateway)
├── data/
│   ├── local/                Database.kt, DraftStore.kt, HomeUiStore.kt  (Room + prefs)
│   ├── model/Models.kt       Session, Message, MessageRole, MessageStatus
│   ├── repository/           HermesRepository.kt (904 lines), RepositoryAudio.kt
│   └── runs/                 RunController.kt, RunEventCodec.kt, TurnText.kt
├── network/                  HermesApiService.kt, AuthInterceptor.kt, TerseOptimizer.kt
├── notifications/            ResponseWatcherService.kt, ApprovalActionReceiver.kt
├── security/                 SecurePrefs.kt
├── update/                   AppUpdateChecker.kt
└── ui/
    ├── screens/              chat/ home/ sessions/ settings/ voice/ terminal/
    ├── components/           ModelPickerSheet, MessageActions, MarkupEditor, ...
    ├── markdown/             MarkdownTableParser
    ├── preview/              FilePreviewSheet, FilePreviewParsers
    ├── theme/                Color, Theme, Type
    └── haptics/, chat/       ResponseMedia, Haptics
```

Largest files (change carefully): `ui/screens/terminal/TerminalScreen.kt` (1120),
`ui/screens/settings/SettingsScreen.kt` (923), `HermesRepository.kt` (904),
`ui/screens/chat/ChatScreen.kt` (large), `ui/screens/voice/VoiceScreen.kt` (758),
`ui/screens/settings/SettingsViewModel.kt` (747). Total ≈ 21.6k lines of Kotlin.

---

## 4. Runtime architecture

```
┌──────────── Compose UI (ChatScreen / Home / Sessions / Settings / Voice / Terminal)
│                 reads Flows, calls ViewModels
├──────────── ViewModels (@HiltViewModel) ── user intent, screen state
├──────────── HermesRepository (@Singleton) ── the only data facade
│      ├── SessionDao / MessageDao (Room = single source of truth for UI)
│      ├── RunController (@Singleton, app-scoped) ── durable turns
│      └── HermesApiService (OkHttp + SSE, Bearer via AuthInterceptor)
└──────────── Hermes Agent gateway on the user's machine
```

Two rules that hold everywhere:

1. **Room is the UI's source of truth.** Screens observe DAOs, not the network. Anything that
   learns something new writes it to Room first.
2. **The server is the source of truth for conversation content.** Local rows are a cache that gets
   refreshed; local-only rows exist only for optimistic UI and pending turns.

---

## 5. Durable turns — `RunController`

This is the most important design decision in the app.

**Problem.** Chat-completions SSE is synchronous: closing the socket (screen switch, process death,
network drop) makes the gateway **interrupt** the agent.

**Solution.** `/v1/runs` decouples the turn from the transport. The run executes server-side
regardless of the phone, its status is pollable, events are re-subscribable, and
stop / steer / approval are explicit routes. `RunController` owns the client half:

- One live turn per session, keyed by session id, in an **app-level `SupervisorJob` scope** —
  never the ViewModel scope, so navigating away cannot cancel it.
- Live state (streaming text, tool trail, pending approval) exposed as `StateFlow`; the chat screen
  re-attaches on reopen.
- Run ids persisted in prefs; `recover()` on app start re-attaches to unfinished turns or finalizes
  completed ones from server truth.
- Room is the single writer: chunks update the placeholder row live, so any screen reading the
  session sees progress without going through `RunController`.

Constants that encode hard-won behaviour (do not tune casually):

| Constant | Value | Meaning |
|---|---|---|
| `POLL_INTERVAL_MS` | 3 s | status fallback when SSE is lost |
| `SLOW_POLL_INTERVAL_MS` | 10 s | degraded polling |
| `STATUS_MISS_LIMIT` | 4 | consecutive 404s (gateway restart / TTL sweep) before settling from transcript |
| `SETTLE_GRACE_MS` | 3 s | wait before believing "no answer" — terminal handler persists asynchronously |
| `STOP_SETTLE_GRACE_MS` | 6 s | a stop the server accepted but never finalized |

`LiveTurn` is an immutable snapshot; the map is the source of truth. It carries
`sessionId, runId, assistantMsgId, userMsgId, query, model, streamingText, toolLines,
pendingApproval, stopping`, plus the Token-Optimizer decision that actually rode the wire payload
(feeding back a *fresh* decision would double-count the model counter).

**Invariant:** never move this scope into a ViewModel, and never let an SSE close be interpreted as
a turn failure. Loss of transport ⇒ poll.

---

## 6. Data layer — `HermesRepository`

### Sessions

- `allSessions: Flow<List<Session>>` straight from `SessionDao`.
- `createSession()` makes a local **UUID** row. Synced sessions are stored under their **server id**
  (no dashes); app-created UUID rows resolve through the `srv_session` continuity map.
  `serverIdFor()` returns null only for never-synced UUID sessions.
- `syncAllSessionsFromServer()` — full server→phone sync, called on open and every 10 s. Pulls
  every Hermes session (Desktop, Telegram, CLI, other phones). Imported rows use the **server id as
  the local id**, so no mapping is needed for them. App-created sessions are recognised via the
  continuity map and skipped.
  - Titles update from server truth **unless renamed locally** (baseline in `sync_title:<id>` prefs).
  - When the server returns a *complete* list, synced rows the server no longer knows are purged
    (desktop-side delete).
  - Model pin is saved for imported sessions so reopening uses the server's model.
- **Delete is two-phase with a 6 s undo grace.** Local rows go immediately; the server DELETE is
  deferred until the grace window expires. Tombstones live in `deleteGrace` (a
  `ConcurrentHashMap.newKeySet`) and are persisted in `hermes_sessions` prefs, so a process kill
  cannot resurrect a session the user already committed to deleting.
  - `flushPendingDeleteGrace()` runs **once per process** — flushing on every poll would destroy
    sessions still inside a live undo window. The tombstone is only removed when the server
    confirms (or 404s) the delete; an offline flush must not un-hide a committed delete.
  - Undoing a *server*-deleted synced session is not possible (the transcript is gone).
- `setSessionArchived()` — local flag first (instant UI), then server PATCH best-effort; the
  desktop sidebar shares the flag.
- `forkSession()` — server-side fork into a new session id, then mirrors rows + model pin +
  continuity map locally so the fork opens instantly.

### Messages

- Optimistic insert with `MessageStatus.SENDING`, advanced to SENT → READ by the turn.
- Telegram-style **queue**: while the agent is busy the user's message shows immediately
  (`insertLocalUserMessage`) and its turn starts after the current response completes.
- `resumeSession()` refreshes the **full** transcript from the gateway on every open (server is
  truth), but **skips while a `RunController` turn is live** — the run owns the rows until it
  completes.
- `refreshTranscriptFromServer()` keeps local rows when the server returns empty/unreachable
  (a fresh session's 0-row list must not wipe a draft the run engine is about to persist).
- `applyServerResponse()` — shared by the push subscription and the catch-up poll. Anchors to the
  **newest assistant row** (not the newest row overall, because a queued user message may sit above
  it), has a **stale-guard** (`ts` older than the bubble it would patch ⇒ reject), and is
  idempotent. Also advances the owning user row's tick to READ.
- `pollServerResponse()` — cheap check first: read `/api/sessions/{id}` (~1 KB) for `message_count`
  and only refetch the full transcript on change. The full transcript is 0.5–5 MB; polling it every
  5 s over Tailscale throttled both the gateway and the UI ("goes in loop").
- `backfillAttachments()` — re-attach media to local assistant rows missing it, matching by content.
- `repairBlankAssistantResponse()` — a blank assistant placeholder means the stream died mid-turn;
  poll up to ~3 min (90 × 2 s) for the detached server generation, patch the **newest** message only,
  and drop the bubble if the turn was genuinely empty.
- `searchMessages()` escapes `%`, `_`, `\` so user input is literal, not wildcard.
- Timestamps from the server are **epoch seconds as float** — must be multiplied by 1000, not
  `optLong`'d.

### Prefs owned by the repository

`chat_font_sp` (one shared `StateFlow`, so a size change lands mid-session with no restart),
`sync_title:<id>`, `srv_session` continuity map, model pins, `deleteGrace` tombstones,
auto-approve, dark theme, caveman/terse.

---

## 7. Network

- `HermesApiService` — OkHttp directly. Bearer token injected by `AuthInterceptor` for **API and
  `/uploads`** (the server requires auth on uploads, so Coil must use the same interceptor; see
  `HermesApp.newImageLoader()`).
- `TerseOptimizer` — Token Optimizer (shorter replies); the decision for a turn is recorded in
  `LiveTurn` and reported back at finish.
- `ServerConfig` (URL + key) saved via `SecurePrefs`; `clearSavedConnection()` is Settings → Log Out.
- Model inventory is **never hardcoded** — it is fetched from the server and cached; the cache is
  invalidated when the server changes or a provider set changes.
- Timeouts: 30 s connect / 60 s read on the Coil client. Long generations are not bound by HTTP
  timeouts because the turn lives in `/v1/runs`.

---

## 8. UI layer

- `ChatScreen.kt` holds `ChatViewModel` plus the tool-call display types
  (`ToolCallInfo`, `ToolCallStatus`). Streaming text is rendered by `StreamingText.kt` /
  `StreamingIndicator.kt`; markdown by `MarkdownText.kt` + `MarkdownTableParser`.
- Tool activity is grouped (`ToolActivityGroup.kt`) and approvals render as `ApprovalCard.kt`;
  approvals also arrive as a notification (`ApprovalActionReceiver`) so the user can answer without
  the app being foregrounded.
- Attachments: `AttachmentPreview.kt`, `FileAttachmentRow.kt`, `ui/preview/FilePreviewSheet.kt`
  (images, multi-page PDF, Office, JSON/code, with safe fallback + "Open externally").
- `ResponseWatcherService` — foreground service that notifies on turn completion **only when the
  app is not visible** (`AppForeground` tracking registered in `HermesApp`).
- Voice: `VoiceCapture.kt` + `encodeWav` upload to server Whisper; TTS comes back as audio and is
  played by `AutoPlayAudio.kt`.
- Terminal: `GatewayPtyClient.kt` + `TerminalAnsi.kt` over `sshj`.

---

## 9. Startup sequence (`HermesApp.onCreate`)

1. `DiagLog.init`
2. `installCrashHandler` → writes `filesDir/crashes/crash_<ts>.txt` (handler must never throw)
3. `runController.recover()` → poll each persisted run id once; finalize completed (notify),
   re-attach still-running
4. `syncCrashDumps()` → upload unsent `crash_*` and rename to `.sent` (exactly-once shipping,
   survives offline→online)
5. register activity lifecycle callbacks for foreground tracking

---

## 10. Build, signing, release

- `debug` **and** `release` both run R8 (`isMinifyEnabled = true`). The debug build is what gets
  sideloaded, and without minify it ships ~8 MB of dead dex.
- `isShrinkResources = false` deliberately: with it on, AGP pads the APK with a ~15 MB zero hole
  (zipalign/page-align artifact) making the file *bigger*.
- `jniLibs.useLegacyPackaging = true` for the same reason (uncompressed page-aligned libs cause a
  ~14 MB zero hole).
- Release signing comes from `local.properties` (`hermes.keystore`, `hermes.storePassword`,
  `hermes.keyPassword`, alias `hermes-mobile`) or `HERMES_*` env. **No debug-signing fallback** — an
  unsigned release is emitted instead, because a debug-signed release breaks in-place updates.
- Output name: `Hermes-Mobile-v${versionName}.apk`.
- `./gradlew :app:assembleDebug`, `./gradlew :app:testDebugUnitTest`.
- Version bumping goes through `scripts/release.sh` only; it also runs `scripts/check_versions.sh`
  (installed as a pre-commit hook). Tag push → CI builds, GPG-signs `SHA256SUMS`, publishes.

---

## 11. Extension guide — where to change what

| I want to… | Touch |
|---|---|
| Add a server call | `HermesApiService` + a passthrough in `HermesRepository` |
| Add a persisted field | `Models.kt`, Room entity + DAO, bump `Database` version + schema |
| Change turn/streaming behaviour | `RunController` (respect the scope + polling invariants) |
| Change how transcripts sync | `HermesRepository.syncAllSessionsFromServer` / `applyServerResponse` |
| Add a chat UI element | `ui/screens/chat/*`, state via `ChatViewModel` |
| Add a setting | `SettingsViewModel` + `HermesApiService` prefs helpers + `SettingsScreen` |
| Add an attachment type | `ui/preview/FilePreviewParsers.kt` + `previewKindFor` |
| Change auth | `AuthInterceptor` — remember Coil uses it too |

---

## 12. Gotchas that have already caused bugs

1. Anchoring a server response to the newest row instead of the newest **assistant** row misaligns
   the conversation when a queued user message is above it.
2. Reading server timestamps with `optLong` yields garbage (they are float seconds).
3. Deleting a synced session locally without hitting the gateway lets the 10 s sync re-import it.
4. Polling the full transcript on a timer throttles the gateway — use `message_count` first.
5. Refreshing the transcript while a run is live fights the run for the rows.
6. Empty server transcript must not overwrite local rows.
7. Crash handler, diag upload, and all "best-effort" syncs must never throw.
8. Unescaped `%`/`_` in search input silently changes results.

---

## 13. Not verified / open questions

- Exact SSE event schema in `RunEventCodec` was not read line-by-line — consult it before changing
  event parsing.
- `RunController` beyond the class header (constants, `LiveTurn`, contract) was not fully read.
- Room schema version / migration history lives in `app/schemas/` — check before editing entities.
- README mentions Swarm mode, per-session model picker and slash commands; the UI entry points were
  not traced end-to-end.
- `docs/` also contains screenshots and possibly design notes not covered here.
