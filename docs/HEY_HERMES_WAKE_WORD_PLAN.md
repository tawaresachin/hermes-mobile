# "Hey Hermes" hands-free wake word — implementation plan

## Goal
Chat hands-free mode must arm a microphone, listen for **"Hey Hermes"** fully on-device,
and on detection start the existing continuous dictation/turn flow — behaviourally the same
as Hermes Agent's wake-word surface (single owner, cooldown, confirmation frames,
pause while a turn owns the mic, resume when idle, optional new session).

## Source of truth (Hermes Agent)
- `~/.hermes/hermes-agent/tools/wake_word.py` — config, capture loop, singleton listener,
  2.0 s fire cooldown, 5 s start timeout, 3 confirmation frames, silence detection.
- `~/.hermes/hermes-agent/tools/wake_word_engines.py` — openWakeWord engine
  (sensitivity 0.6 = raw threshold, frame 1280 samples = 80 ms @16 kHz).
- `~/.hermes/hermes-agent/tools/wakewords/hey_hermes.tflite` (206 KB, Apache-2.0,
  trained with the openWakeWord pipeline).
- pyopen-wakeword 1.1.0 ships the shared `melspectrogram.tflite` (~?) and
  `embedding_model.tflite` (1.33 MB) used before the classifier.

## Architecture (Android, on-device, no cloud audio before the wake)
1. **Detector** (`wake/WakeWordDetector.kt`): TFLite `Interpreter`s for
   melspectrogram → embedding → `hey_hermes` classifier, 16 kHz mono PCM16,
   1280-sample frames, rolling feature buffers exactly as pyopen-wakeword
   (`OpenWakeWordFeatures.process_streaming`), score ≥ sensitivity, N consecutive
   over-threshold windows, 2 s cooldown, reset() between sessions.
2. **Audio owner** (`wake/WakeWordService.kt`): a single foreground service
   (`microphone` type, persistent notification, stop action) that owns AudioRecord,
   the detector and the fire callback. Pause/resume API — only one mic owner, same
   rule as the agent (`WakeWordInUse`). Audio focus + routing handled here.
3. **Chat integration**: the hands-free toggle gets a third state — "Wake word armed".
   Armed → service listens; on fire → chime/haptic, optional new chat session
   (Hermes `start_new_session`), release mic, start the existing
   `startHandsFreeDictation` path; after the reply, re-arm automatically.
   Voice screen continues to own the mic while a conversation is active.
4. **Settings**: enable/disable, sensitivity slider, "start a new chat on wake",
   microphone permission gate, battery-optimisation hint. Default **off**.

## Security & privacy
- Explicit opt-in only; no wake service without RECORD_AUDIO + POST_NOTIFICATIONS.
- Visible foreground notification whenever the mic is open; stop action.
- No audio leaves the device until a wake fires; then only the command goes through
  the existing STT path (device or gateway).
- No storage of raw audio; detector buffers bounded; no logs of PCM.
- Service stops on logout, app background beyond a configurable policy (default:
  keep armed while the app is foreground; optional background arming with a clear
  notification), and on microphone loss.

## Performance
- 80 ms frames, int16; TFLite CPU with 1–2 threads, NNAPI optional; target <1% CPU
  and no wakelock beyond the foreground service; reuse interpreters; zero per-frame
  allocations; bounded buffers; battery/thermal test over 30 min.
- Model assets ~2.5 MB total, loaded once.

## Testability
- Bundle two fixtures: `hey_hermes.wav` (generated with macOS `say`) and a negative
  `hello_there.wav`. A debug "self-test" action feeds each through the detector and
  shows score/fire — runnable in mac.apk without a real microphone.
- JVM tests for the frame/score state machine (cooldown, confirmation, reset).
- Instrumented test for detector fixture scores (device/emulator).
- Live E2E: arm in Chat, inject fixture (debug) → wake fires → dictation starts;
  pause/resume with an active turn; service stop.

## Delivery phases
1. Models + TFLite dependency + detector + fixtures + self-test (no UI change).
2. Foreground service + Chat hands-free third state + settings + notification.
3. Hardening (pause/resume, audio focus, logout/background, silence, errors),
   performance measurement, docs, tests.
4. Release app 0.1.27 (plugin unchanged), signed APK, install and verify.

## Acceptance
- Saying "Hey Hermes" (or the fixture) wakes Chat hands-free in <1 s, once per phrase
  (2 s cooldown), with a visible notification while armed.
- No cloud request/audio before the wake; no wake without opt-in.
- Active turn pauses listening and re-arms after; logout stops the service.
- CPU <1% while armed, no leaks in a 30 min soak, no ANR.

## Attribution
Bundled models: openWakeWord (Apache-2.0) + Hermes `hey_hermes.tflite`; include
`NOTICE`/asset README with provenance.

## Always-on mode (Settings toggle = master arm)

Feasible, with these constraints and decisions:

- **Master switch**: enabling "Hey Hermes" in Settings starts the microphone foreground
  service immediately and persists. Any cold start of the app re-arms it automatically;
  disabling stops the service and removes the notification. The Chat long-press remains a
  manual session override, not a second button.
- **Wake from anywhere**: a single app-scoped coordinator listens to wake events. If Chat
  is visible it starts dictation directly; otherwise it posts a high-priority
  "Hey Hermes — listening" notification with a full-screen intent to MainActivity
  (assistant use case), which navigates to Chat and starts dictation. If the OS refuses
  the background activity start, the notification tap is the fallback.
- **Full voice turn**: wake → dictation auto-send → assistant stream → spoken reply through
  the existing gateway TTS endpoint (reuse VoiceViewModel/AutoPlayAudio), then re-arm.
  A "Speak replies" setting (default on) gates speech; silent mode still re-arms.
- **Mic exclusivity**: detector pauses for dictation/voice screen/assistant turn and
  resumes when idle (already modelled by WakeWordRunController).
- **Limits**: Android 14+ restricts while-in-use mic services started from the background;
  arming from Settings, a visible app start, or a foreground service already running is
  allowed. Reboot auto-start is a separate, optional boot receiver (documented; may be
  rejected by OEMs) — default off.
- **Testing**: debug simulate-wake drives coordinator → Chat → dictation → TTS → re-arm,
  plus a background/wake-notification case; unit tests for coordinator transitions.
