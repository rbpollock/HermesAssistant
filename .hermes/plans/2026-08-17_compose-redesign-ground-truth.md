# HermesAssistant — Compose Redesign: Ground-Truth Map (Section 0)

> Source of truth: read from the actual code on 2026-08-17 (v1.8.1).
> This map exists so the redesign never guesses — every screen, data
> flow, and wire shape below was verified against the files listed.

## 1. Current UI (legacy Views/XML — confirmed, no Compose anywhere)

Single-Activity app, Views + XML layouts, no Fragment nav.

| Screen | File | Notes |
|---|---|---|
| Main | `MainActivity.kt` (~1,150 lines) + `activity_main.xml` | chatSection (session chips + scroll history + text input row), collapse band (now a slim strip when collapsed), assistant panel (StatusRingView + status + speak button + keyboard/settings icons) |
| Settings | `SettingsActivity.kt` + `activity_settings.xml` | host/port, version, GitHub link, update check/install, Audio switches (BT-only, mute) |
| Overlay | `HermesForegroundService.kt` + `overlay_panel.xml` | bottom-third "over other apps" window: expand bar + status + hidden speak button |
| Status ring | `StatusRingView.kt` (custom View) | 150dp animated ring, states IDLE/CONNECTED/LISTENING/THINKING/SPEAKING |

Components (v1.7.0 refactor — all plain objects/classes, no new daemons):
- `RelayClient.kt` — WS /chat/stream + reconnect + dispatch (Listener interface)
- `AudioPlayer.kt` — FIFO playback queue (MediaPlayer MP3 + TTS alerts), watchdog, BT/mute routing via `AppSettings`, progressive pipe streaming
- `VoiceInput.kt` — Google STT + Vosk dictation state machine, one-chime transitions
- `ChatHistoryStore.kt` — chat_history.json (50 msg ring) + pending_queue.json
- `SessionStore.kt` — sessions.json (chips list)
- `ServerConfig.kt` — host/port prefs; `AppSettings.kt` — BT-only/mute prefs
- `ChimePlayer.kt` — chime WAVs via MediaPlayer
- `UpdateChecker.kt` — atom-feed update check + install
- `NotificationReplyReceiver.kt` — shade inline reply → HTTP /chat/message
- `HermesForegroundService.kt` — Vosk wake word, dictation, overlay, FGS notification, wake-word HTTP reply speak-back
- VoiceInteraction service/session stubs (default-assistant registration)

## 2. WebSocket contract — `/chat/stream` (server.py:330)

**Client → server:** one text frame per message.
- Plain text = normal voice chat → daily `android_YYYY_MM_DD` session
- JSON `{"message": "...", "session_id": "..."}` = targeted reply

**Server → client:** JSON frames + binary audio bytes:

| Frame | Meaning |
|---|---|
| `{"type":"status","message":"Thinking..."}` | sent first for every inbound message |
| `{"type":"status","message":"Speaking..."}` | right before audio streaming |
| `{"type":"text","message":"<reply>"}` | reply text (once per reply) |
| `<binary MP3 bytes>` | streamed chunks as edge-tts generates them |
| `{"type":"audio_end"}` | end of the audio stream |
| `{"type":"notify", ...}` | relayed hook event (schema below) |
| `{"type":"status","message":"Sent to live session"}` | injected-live path |

Live-session injection: when the target session is under screen/tmux,
the server types the message in and does NOT run a one-shot — the
reply arrives later via the hook notify (`event=="injected"` confirm +
session's own "Hermes finished").

## 3. HTTP endpoints

- `POST /chat/message` `{"message","session_id"}` →
  `{"ok":true,"session_id","reply","injected_live"}` (one-shot; used by
  shade replies and wake-word dictation)
- `POST /hermes-events` — hook events from notify_hermes.py

## 4. Notify schema (server.py:292)

```json
{
  "type": "notify",
  "kind": "response" | "question" | "approval",
  "title": "Hermes finished · <session title>" | "Hermes is waiting for your input" | "Hermes needs your approval",
  "message": "<response_text or question or approval description>",
  "session_id": "...",
  "event": "on_session_end" | "post_tool_call" | "pre_approval_request" | "injected",
  "host": "<hostname>",
  "already_spoken": true|false
}
```

- `notify_hermes.py` filters at source: only on_session_end,
  post_tool_call(clarify only), pre_approval_request; drops the phone's
  own one-shot sessions (SELF_CWD_PREFIXES); attaches `response_text`
  (≤4000) and `session_title` (≤200) from the local session DB.
- `already_spoken=true` (90s window, one-shot) means the reply was just
  streamed over WS — the app must NOT re-read it as an alert.

## 5. Data flows (current)

- **Wake word** → FGS Vosk → dictation → HTTP `/chat/message` (service
  speaks reply via its own TTS, BT/mute-gated) + broadcast history update
- **Tap/voice** → VoiceInput (Google STT or Vosk offline) → WS →
  server one-shot → text frame + audio chunks → AudioPlayer (progressive
  pipe) → auto-listen (2.5s, user-initiated only)
- **Notify** → WS notify frame → MainActivity handleNotify (dedupe →
  history bubble → system notification + TTS alert if routing allows)
- **Shade reply** → NotificationReplyReceiver → HTTP /chat/message →
  history markInjected + append reply
- **Offline** → Vosk dictation → chatHistory.enqueue → flush on onOpen

## 6. FUTURE.md reconciliation

- No UI-rewrite item tracked there — no conflict with this brief.
- Backlog items stay OUT of scope for this pass: per-session muting,
  batch endpoint, larger Vosk model, and relay auth (auth explicitly
  stays as documented — README:31 "no authentication or encryption").
- #4/#5 (offline queue, history) are IMPLEMENTED — the redesign must
  preserve both behaviors.

## 7. Environment (Compose planning)

- AGP 9.2.1, Kotlin 2.3.0 (via Kotlin Android plugin on the classpath),
  Gradle 9.4.1, minSdk 24, targetSdk 36, Java 11
- Version catalog `gradle/libs.versions.toml` (currently no Compose
  entries) — Compose BOM + compiler plugin must be added there
- No auth on relay — do not touch server security posture
