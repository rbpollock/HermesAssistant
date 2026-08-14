# Hermes Assistant — Future Development TODO

Two features were requested but intentionally deferred. This file tracks
the designs so they're easy to pick up later.

---

## 4. Offline transcription queue (send big dump on reconnect)

**Goal:** Let the user dictate messages to Hermes even when the phone has no
connectivity to the server (Tailscale down, server off, airplane mode).
The messages are transcribed locally and queued; when the WebSocket
reconnects, everything is flushed to Hermes in one batch.

**Design notes:**
- Vosk is already bundled in the app (`assets/model`) and used only for the
  "Hey Hermes" wake word. It can also do full-phrase transcription: the same
  `Recognizer` returns `text` in `onResult`. The current small model
  (`vosk-model-small-en-us-0.15`) is tuned for wake-word accuracy; for
  dictation you may want the full `vosk-model-en-us-0.22` (larger, ~1.8GB
  unpacked — decide whether that's worth the APK size).
- Persist queued messages in a plain JSON file under `cacheDir` or
  `filesDir` (`pending_queue.json`). Survives app restarts.
- Detect "offline": `isConnected == false` after a send attempt, or a
  periodic heartbeat. When offline, the STT flow writes to the queue instead
  of sending.
- On `onOpen`, if the queue is non-empty, send them one-by-one (or ask the
  server for a batch endpoint `POST /chat/batch` with `{"messages": [...]}`).
- UI: show a small badge/count on the ring view ("3 queued") so the user
  knows the dump is pending. Feature 5's history view should show queued
  items with an "offline" marker.

**Relevant files:** `MainActivity.kt` (`onResults`, `connectWebSocket`),
`server.py` (optionally a `/chat/batch` endpoint), Vosk usage in
`startVoskWakeWord()`.

---

## 5. Message history view (last N sent/received)

**Goal:** Show the last several sent/received messages inside the phone app
(chat log), instead of only the most recent status line.

**Design notes:**
- Maintain an in-memory ring buffer of the last ~50 exchanges:
  `data class ChatMessage(val role: String, val text: String, val ts: Long)`
  (`role` = "user" | "hermes" | "notify").
- Persist to `filesDir/chat_history.json` (or SharedPreferences/DataStore)
  so history survives restarts.
- UI: a RecyclerView tucked above the status panel, or a scrollable
  `LinearLayout` inside a bottom sheet. Given the half-screen overlay design,
  a swipe-up / expand gesture to reveal history fits best.
- Hook points in `MainActivity.kt`:
  - `onResults` → append `ChatMessage("user", userText)`
  - `onMessage` type `"text"` → append `ChatMessage("hermes", message)`
  - `handleNotify` → append `ChatMessage("notify", title + message)`
- Feature 4's queued offline messages should also appear here with an
  "offline · queued" tag.

---

## Nice-to-have backlog

- **Foreground service:** keep the WebSocket + wake word alive when the app
  is backgrounded (currently Android may kill the socket; the app
  reconnects on foreground). Required for true "always listening for
  notifications" behavior with #2.
- **Wake-word re-arm toggle:** some users want the auto-listen loop (#1) to
  return to wake-word mode instead of raw listening.
- **Per-session notification muting:** pick which session IDs/cwds should
  NOT produce phone alerts (mirror of the `SELF_CWD_PREFIXES` filter in
  `notify_hermes.py`, but user-controlled).
