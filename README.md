# Hermes Assistant

A hands-free voice assistant that lives on your Android phone and talks to
Hermes Agent sessions running on your own machines — over your own network
(Tailscale). It listens for wake words, streams audio replies, pushes
notifications for *any* Hermes session on *any* host, supports typed text,
offline dictation, and targeted replies to specific sessions.

```
┌─────────────────────┐         ┌──────────────────────────┐
│  Android phone      │         │  Relay server (Linux)    │
│  (this app)         │  WS/HTTP│  streaming_backend/      │
│                     │◄───────►│  FastAPI + edge-tts      │
│  - voice chat       │         │  port 8000               │
│  - notifications    │         └──────┬───────────────────┘
│  - session chips    │                │ shell hooks (HTTP POST)
│  - typed text       │                ▼
└─────────────────────┘         ┌──────────────────────────┐
                                │ Hermes hosts             │
                                │ (this PC, servers, ...)  │
                                │ each runs notify_hermes  │
                                │ + its own hermes CLI     │
                                └──────────────────────────┘
```

## Repository layout

| Path | What it is |
|---|---|
| `app/` | The Android app (Kotlin, Gradle). |
| `streaming_backend/server.py` | The relay server: WebSocket voice chat + TTS + notify relay. |
| `streaming_backend/notify_hermes.py` | Shell-hook script installed on **every Hermes host**; posts session events to the relay. |
| `streaming_backend/shell-hooks-allowlist.json` | Pre-seeded hook approvals (see below). |
| `FUTURE.md` | Development notes / backlog. |

---

## ⚠️ Security status: NOT ENABLED

**The relay server has no authentication or encryption.** Anyone who can
reach `http://<server>:8000` can:

- speak to your Hermes sessions over the WebSocket,
- read/send notification events,
- run one-shot Hermes prompts via `POST /chat/message`.

The intended deployment is therefore **Tailscale-only**: the server binds
`0.0.0.0:8000` but should only ever be reachable via your tailnet IPs.
Do **not** expose port 8000 to the public internet, port-forward it, or
publish it on a cloud firewall. If you need access from outside your
tailnet, add real auth (API token / mTLS / reverse proxy) first.

This is a known, deliberate gap for a personal/homelab tool — flagged here
and in the app's settings screen.

---

## The Android app

### Features

- **Hands-free voice chat** with Hermes Agent over WebSocket. Audio replies
  stream back and play through a unified FIFO queue (TTS alerts never talk
  over response audio).
- **Wake word + auto-listen**: say "Hey Hermes", or tap to speak; after a
  reply the app listens again automatically.
- **Bluetooth-gated audio**: responses auto-play only on a connected BT
  device; otherwise they're saved and playable by tap.
- **Notifications from every host**: when any Hermes session on any machine
  ends, asks a question (`clarify`), or requests approval, your phone gets a
  notification with the session title + actual response text.
- **Direct reply from the notification shade**: type an answer right in the
  notification (works even if the app process is dead).
- **Session chips**: sessions that have notified the phone appear as chips;
  tap to route your next message (voice or text) to that exact session.
- **Tap-to-target history**: tap any message in the conversation log to
  select the session it belongs to.
- **Typed text input** (circular keyboard icon, top-right of the panel) —
  same pipeline as voice, including targeted replies.
- **Offline dictation queue**: when the phone can't reach the server
  (Tailscale down, airplane mode), Vosk transcribes locally and messages
  queue up, flushing automatically on reconnect.
- **Configurable server**: gear icon (top-left of the panel) → set the
  relay server's IP/hostname and port.
- **Foreground service**: keeps the app alive in the background so
  notifications arrive reliably (prevents OEM background-freezing).
- **Custom launcher icon**: the app's green status-ring motif.

### Build

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Requires Android SDK; the app targets API 36, minSdk 24. The Vosk offline
model is bundled as an AAR (see `download_vosk_aar.py` / `find_vosk.py`).

### Install / update

1. Build the APK (above) or grab a release from
   <https://github.com/rbpollock/HermesAssistant/releases>.
2. Install on the phone (`adb install` or open the APK on-device).
3. Open the app once: grant notifications (Android 13+), record audio, and
   let the foreground service start ("Hermes Assistant is running").
4. Set the server in Settings (gear icon) if it's not the default.
5. Optional: in Android settings set **Battery → Unrestricted** for the app
   so backgrounding never suspends it.

---

## The relay server (`streaming_backend/server.py`)

FastAPI app on port 8000. Single process, no DB, no config file — designed
to be small.

### Endpoints

| Endpoint | Purpose |
|---|---|
| `WS /chat/stream` | Phone voice chat: text in → Hermes reply text + edge-tts audio out. Accepts plain text (daily android session) or `{"message": ..., "session_id": ...}` (targeted reply). |
| `POST /chat/message` | One-shot text to a session (used by notification direct-reply). `{"message": ..., "session_id": ...}` → `{"ok", "session_id", "reply"}`. |
| `POST /hermes-events` | Receives shell-hook events from any Hermes host; buffers (max 20) and relays to connected phones as `notify` messages. |

### Run it

```bash
# on the relay host (requires Python 3.10+, fastapi, uvicorn, edge-tts,
# and the `hermes` CLI on PATH)
cd streaming_backend
uvicorn server:app --host 0.0.0.0 --port 8000
```

The server shells out to `hermes -z <message> --continue <session>` to
produce replies, and `edge-tts` (Microsoft neural voices) for speech.

---

## The notify hook (`streaming_backend/notify_hermes.py`)

Install this script on **every machine that runs Hermes** (the PC, the
server, anywhere). It reads Hermes shell-hook events from stdin, enriches
session-end events with the session title + last response text (from that
host's local `state.db`), and POSTs them to the relay.

### Install on Linux (e.g. the server)

```bash
cp streaming_backend/notify_hermes.py ~/.hermes/notify_hermes.py
chmod +x ~/.hermes/notify_hermes.py
```

### Install on Windows

```bash
cp streaming_backend/notify_hermes.py \
  "$LOCALAPPDATA/hermes/scripts/notify_hermes.py"
```

### Configure hooks in `config.yaml`

Add a `hooks:` block (same shape on every host; on Windows use the venv
python as interpreter):

```yaml
hooks:
  on_session_end:
    - command: /home/service/.hermes/notify_hermes.py        # Linux
      # command: C:/Users/<you>/AppData/Local/hermes/hermes-agent/venv/Scripts/python.exe C:/Users/<you>/AppData/Local/hermes/scripts/notify_hermes.py   # Windows
      timeout: 10
  post_tool_call:
    - command: /home/service/.hermes/notify_hermes.py
      matcher: 'clarify'
      timeout: 10
  pre_approval_request:
    - command: /home/service/.hermes/notify_hermes.py
      timeout: 10
```

The relay URL defaults to `http://100.123.127.108:8000/hermes-events`;
override with the `HERMES_NOTIFY_RELAY` env var if your relay lives
elsewhere.

> Hooks register at **session start**. After changing the hook config,
> script, or allowlist, start a NEW Hermes session for the changes to take
> effect.

### Shell-hooks allowlist

Hermes asks to approve hook commands on first use. `shell-hooks-allowlist.json`
pre-seeds those approvals so no TTY prompt is needed. After every
`notify_hermes.py` re-deploy, update `script_mtime_at_approval` to the
script's current mtime, on both the host and the copy in this repo:

```bash
# Linux
date -u -r ~/.hermes/notify_hermes.py +'%Y-%m-%dT%H:%M:%S.%6NZ'
# then paste into the allowlist, and push the same file back to the host
```

Check health with `hermes hooks doctor`.

---

## How a notification flows (end to end)

1. A Hermes session on any host finishes / asks a question / requests
   approval.
2. The host's shell hook runs `notify_hermes.py`, which POSTs the event
   (enriched with title + response text) to the relay.
3. The relay buffers it (max 20) and pushes it to every connected phone as
   a `notify` WebSocket message.
4. The app shows a system notification with the session title + text.
5. Tap the notification → app opens on that session's chip; or tap **Reply**
   → type an answer → `POST /chat/message` → that session continues with
   your answer.

---

## Releases

See <https://github.com/rbpollock/HermesAssistant/releases>. Notable:
v1.4.x offline dictation + history, v1.5.x notification context + targeted
replies + audio queue, v1.6.x typed input, session chips, notification
deep-link + direct reply, configurable server, foreground service.

## Roadmap / known limits

See `FUTURE.md`. Highlights: per-session notification muting, larger Vosk
model for dictation, optional batch endpoint, and — most importantly —
**real security** (auth on the relay) before exposing anything beyond the
tailnet.

## License / philosophy

Open source, no third-party API keys required. The stack is deliberately
minimal: one small relay server + one hook script per host + one Android
app.
