# Hermes Desktop → Android Port (Path 1): tui_gateway client

> **For Hermes:** implement milestone-by-milestone; verify each task's checkpoints on-device before moving on.

**Goal:** Turn HermesAssistant into a native Compose client for Hermes' `tui_gateway` JSON-RPC/WebSocket API — the same backend the Hermes Desktop app uses — while keeping notifications, voice-to-text, wake word, and slide-up draw-over-apps behavior.

**Architecture:** Phone = thin client (Compose UI + JSON-RPC over WS). Brain = `hermes serve` running on irl-server-01 (v0.20.4, already installed). This bypasses the streaming_backend relay for the new UI; the relay + old app versions keep working unchanged (no server.py changes).

**Tech Stack:** Kotlin, Jetpack Compose Material3 (existing), OkHttp WebSocket (existing), org.json (existing, no new deps), existing FGS/Vosk/AudioPlayer/VoiceInput.

---

## Protocol ground truth (verified against local source, 2026-08-21)

- `hermes serve --host <ip> --port 9119 --insecure` on irl-server-01 (v0.20.4) serves:
  - WS endpoint: `ws://<ip>:9119/api/ws` — JSON-RPC 2.0 frames, same `tui_gateway.dispatch` surface the TUI uses over stdio.
  - REST endpoints for free: `/api/status`, `/api/fs/list`, `/api/fs/read-text`, `/api/audio/transcribe`, `/api/audio/speak`, `/api/sessions`, `/api/config`, `/api/model/options`.
- Frame shape (`JsonRpcFrame`): `{id, method, params, result, error}`. Events come as notifications: `{method: "<event.type>", params: {payload...}}` — no id.
- Methods the app needs (verified in `tui_gateway/server.py`):
  - `session.create` params: `{cols?, messages?, title?, cwd?, profile?}` → result contains `sid`.
  - `session.most_recent`, `session.list`, `session.resume` `{session_id}` (or key), `session.close`, `session.interrupt`, `session.delete`, `session.title`.
  - `prompt.submit` params: `{session_id, text}`. Error 4009 "session busy" if the session is already running.
  - Responses: `clarify.respond`, `approval.respond`, `sudo.respond`, `secret.respond` (all take `session_id` + payload).
  - `config.get` / `config.set`, `model.options`.
- Event types (GatewayEventName) the UI renders:
  - `gateway.ready` (on connect), `session.info`, `session.usage`
  - `message.start`, `message.delta`, `message.interim`, `message.complete`
  - `thinking.delta`, `reasoning.delta`, `reasoning.available`
  - `status.update` (`{kind, text}`), `tool.start` (`{name, ...}`), `tool.progress`, `tool.complete` (`{name, status}`), `tool.generating`
  - `clarify.request`, `approval.request`, `sudo.request`, `secret.request`, `background.complete`, `error`
- Auth/security model (v0.20.4 — `--insecure` is now a no-op; non-loopback binds ALWAYS require an auth provider):
  - Configured: `dashboard.basic_auth` in server ~/.hermes/config.yaml (username `robbie`, scrypt hash; plaintext creds in server ~/gateway_pw.txt + local C:\tmp\gateway_pw.txt).
  - Login: `POST /auth/password-login` `{provider:"basic", username, password}` → 200 + Set-Cookie `hermes_session_at` (12h) / `hermes_session_rt` (30d) / `hermes_session_provider`.
  - WS ticket: `POST /api/auth/ws-ticket` (session cookie) → `{ticket, ttl_seconds:30}` single-use; connect `ws://host:9119/api/ws?ticket=<ticket>` — mint fresh per connect.
  - REST: session cookie on every /api/* call (OkHttp CookieJar); on 401 → re-login.
  - Host-header middleware: client MUST use the exact bound host (`100.123.127.108:9119`) as Host — automatic when connecting directly to that IP.
  - Upgrade path later: hermes gateway token/OAuth.
- Wire format (VERIFIED live 2026-08-22):
  - Request: `{id, method, params}` (no "jsonrpc" field required); response `{id, result}` / `{id, error:{code,message}}`.
  - Events: `{method:"event", params:{type, payload, session_id?}}` — dispatch on `params.type` (mirrors `handleMessage` in apps/shared/src/json-rpc-gateway.ts).
  - `session.create` → `result.session_id` (8-hex); `prompt.submit` → `{status:"streaming"}` then event stream.
  - Observed stream: gateway.ready(skin) → sessions.changed → session.info(model/provider/yolo/approval_mode/tools) → message.start(null payload) → session.title → thinking.delta{text} → message.delta{text} → reasoning.available{text} → message.complete{text, usage}.
  - Server session.info: `approval_mode: off`, `yolo: true` — approval.request rare; still implement responders for parity.
- Desktop client semantics to mirror (`apps/shared/src/json-rpc-gateway.ts`): connect timeout 15 s, request timeout 120 s, pending-call map keyed by id, reconnection on close.

---

## Milestone 0 — Server: `hermes serve` on irl-server-01 ✅ DONE 2026-08-22

**Decision (resolved):** Robbie approved; screen-supervised, not a daemon.

### Task 0.1: Launch serve in a screen session

```bash
ssh service@irl-server-01
screen -dmS hermes_gateway bash -c '/home/service/.local/bin/hermes serve --host 100.123.127.108 --port 9119 --insecure; exec bash'
```
- Attach: `screen -x -RR` then `Ctrl-a n` to cycle to `hermes_gateway` (or `screen -r hermes_gateway`).
- Verify listening: `ss -tlnp | grep 9119`.

### Task 0.2: REST smoke test

```bash
curl http://100.123.127.108:9119/api/status
```
Expected: JSON with hermes version info (no 401).

### Task 0.3: WS round-trip smoke test

Script below runs on the server with the bixby_venv python (has `websockets`). Save as `~/streaming_backend/smoke_tui_gateway.py`:

```python
"""Smoke test: tui_gateway JSON-RPC round trip. Run from irl-server-01:
~/bixby_venv/bin/python ~/streaming_backend/smoke_tui_gateway.py [ws_url]"""
import asyncio, json, sys
import websockets

URL = sys.argv[1] if len(sys.argv) > 1 else "ws://100.123.127.108:9119/api/ws"
EVENT_TYPES = {}

async def main():
    async with websockets.connect(URL) as ws:
        print("connected")
        seen_ready = False
        while not seen_ready:
            ev = json.loads(await asyncio.wait_for(ws.recv(), 15))
            print("EVENT", ev.get("method"), json.dumps(ev.get("params"))[:160])
            seen_ready = ev.get("method") == "gateway.ready"
        # create session
        await ws.send(json.dumps({"jsonrpc": "2.0", "id": 1, "method": "session.create", "params": {}}))
        sid = None
        while sid is None:
            resp = json.loads(await asyncio.wait_for(ws.recv(), 15))
            if resp.get("id") == 1:
                sid = resp["result"]["sid"]
                print("SESSION", sid)
        # submit a trivial prompt
        await ws.send(json.dumps({"jsonrpc": "2.0", "id": 2, "method": "prompt.submit",
                                  "params": {"session_id": sid, "text": "Reply with the single word: OK"}}))
        deadline = asyncio.get_event_loop().time() + 90
        while asyncio.get_event_loop().time() < deadline:
            ev = json.loads(await asyncio.wait_for(ws.recv(), 30))
            m = ev.get("method")
            EVENT_TYPES[m] = EVENT_TYPES.get(m, 0) + 1
            print("EVENT", m, json.dumps(ev.get("params"))[:200])
            if m == "message.complete":
                print("--- transcript complete ---")
                break
        print("EVENT COUNTS:", json.dumps(EVENT_TYPES, indent=2))
        # cleanup
        await ws.send(json.dumps({"jsonrpc": "2.0", "id": 3, "method": "session.close",
                                  "params": {"session_id": sid}}))

asyncio.run(main())
```

Expected: `gateway.ready`, then `session.info`, then `message.start` → `message.delta`* → `message.complete`, `status.update`, and `tool.*` events. This validates the exact wire contract the Android client will implement.

### Task 0.4: Reconnect/restart note

Document (in repo README or server notes): restarting serve is `screen -S hermes_gateway -X quit` + relaunch; the gateway is stateless across restarts (sessions persist via server hermes config/db).

**Checkpoint:** Tasks 0.1–0.3 green → the protocol is proven before any Android work.

---

## Milestone 1 — Android: transport + transcript, keep all existing UX

**Status 2026-08-22: transport done + shipped as v2.0.0-alpha1.** GatewayClient/Auth/Api/Events/Bridge/OneShot written; ViewModel wired (gateway mode default ON, session.create + prompt.submit, streaming events -> history + local TTS + shade notification); FGS wake-word routes via GatewayBridge; notification inline reply does one-shot gateway submit; Settings has toggle + port/user/pass + Diagnostics rows. Sheet rework (transcript + tool chips) = next chunk.

New files (all under `app/src/main/java/com/example/hermesassistant/`):

### Task 1.1: `GatewayClient.kt` — JSON-RPC WS transport (core)

Port of `JsonRpcGatewayClient` semantics. Skeleton:

```kotlin
class GatewayClient(private val url: String, private val scope: CoroutineScope) {
    enum class State { IDLE, CONNECTING, OPEN, CLOSED, ERROR }
    private var ws: WebSocket? = null
    private val pending = mutableMapOf<String, CompletableDeferred<JSONObject>>()
    private val handlers = mutableMapOf<String, MutableList<(JSONObject) -> Unit>>()
    private var nextId = 0

    fun connect() { /* OkHttp WS, pingInterval(15s), readTimeout(0) — the 10s trap */ }
    fun send(method: String, params: JSONObject): JSONObject /* request with id, await result/error */
    fun notify(method: String, params: JSONObject) /* fire-and-forget */
    fun on(event: String, handler: (JSONObject) -> Unit)
    fun disconnect() / fun interrupt() /* abort current wait; used by UI cancel */
}
```
- OkHttp `readTimeout(0)` + `pingInterval(15s)` — mandatory (android-kotlin skill: 10-second read timeout trap).
- `onMessage` dispatch (mirrors reference client): frame.id != null → complete pending (result | error.message); else frame.method=="event" && params.type → handlers[params.type].
- Plus `AuthManager`: password-login → persist cookies (okhttp CookieJar backed by SharedPreferences); mint ticket before each connect; re-login on 401; refresh on expiry.
- `onMessage`: if frame has `id` → complete the pending deferred (result or error); else dispatch to `handlers[method]`.
- Errors: JSON-RPC error object → throw with code/message; `session busy` (4009) surfaced distinctly.
- State machine + `StateFlow<State>` for Diagnostics UI; auto-reconnect with backoff on close (unless closed intentionally).
- Threading: all callbacks marshalled onto the ViewModel scope (Main dispatcher).

### Task 1.2: `GatewayEvents.kt` — typed event model

Data classes mirroring GatewayEventName (payload fields as JSONObject passthrough to start; typed accessors added as UI needs them). Event set to render in M1: gateway.ready, session.info, status.update, message.start/delta/complete, tool.start/progress/complete/generating, error.

### Task 1.3: `GatewayApi.kt` — typed calls

```kotlin
suspend fun createSession(): String   // session.create -> sid
suspend fun mostRecentSession(): String?
suspend fun resumeSession(id: String)
suspend fun submitPrompt(sessionId: String, text: String)   // prompt.submit
suspend fun interrupt(sessionId: String)                    // session.interrupt
suspend fun closeSession(sessionId: String)
fun respond(method: String, sessionId: String, payload: JSONObject) // clarify/approval/sudo/secret
```

### Task 1.4: Wire into `AssistantViewModel.kt`

- Add `private val gateway = GatewayClient(gatewayUrl, viewModelScope)` beside `relay`.
- `wireRelay()` (line ~138) gains a gateway path: subscribe to the M1 event set; map message.delta → the same streaming-text state the relay feeds today (keep the existing state shape so the UI barely changes).
- `send()` (line ~385) and TAP-TO-SPEAK path (line ~639) → `gateway.submitPrompt(sessionId, text)` when gateway mode is active; fall back to relay for legacy sessions.
- `relay.cancel()` sites → also `gateway.interrupt()`.
- Session lifecycle in ViewModel init: `mostRecentSession()` → resume, else `createSession()`; persist sid in `SessionStore` (reuse existing store).

### Task 1.5: Sheet rework — transcript + tool activity

- Keep TOP-EDGE anchors (PEEK=0.84 / HALF=0.5 / FULL=0.06) and `.offset{requireOffset()}` translation (existing pattern — do not regress).
- Sheet content becomes a `LazyColumn` transcript: user/assistant bubbles from existing `ChatHistoryStore` plus streaming deltas; a compact status line for `status.update`; tool activity rendered as chips — `tool.start` (spinner) → `tool.complete` (check/x) with tool name + status, collapsing to a single "N tools" row at FULL (mirrors desktop's structured tool summaries).
- Orb behavior unchanged: visible at PEEK/HALF, hidden at FULL.

### Task 1.6: Keep-and-rewire the existing UX (no feature loss)

- Wake word (Vosk in `HermesForegroundService`): unchanged — opens the sheet, arms mic → submit via gateway.
- Voice-to-text (`VoiceInput.kt`) + TTS (`AudioPlayer.kt`): unchanged; TTS triggers on `message.complete` (existing audio path).
- Notifications + `NotificationReplyReceiver`: inline reply → `gateway.submitPrompt` instead of relay `send`.
- Draw-over-apps (`AssistantOverlay.kt`, SYSTEM_ALERT_WINDOW): unchanged.
- FGS bootstrap in VM init (AppViewModelProvider) — already in place; do not regress.

### Task 1.7: `ServerConfig.kt` + Settings/Diagnostics

- Add gateway URL (default `ws://100.123.127.108:9119/api/ws`) to `ServerConfig`; Settings screen gains a gateway URL field (keep relay URL for legacy).
- Diagnostics section: gateway `State` (from StateFlow), last gateway event type, last error (honest real exception message — house rule).

### Task 1.8: Version bump + OTA test cycle

- Bump version to `2.0.0-alpha1` (new transport = major).
- Build APK via Gradle, publish GitHub release with `gh`, OTA install on roberts-s22.
- On-device checklist: wake word → sheet; voice question → streamed answer + TTS; tool events render; notification reply round-trip; overlay floating orb works over another app; kill/restart app → session auto-resumes.

---

## Milestone 2 — desktop feature parity (outline, after M1 proves out)

- Approval/sudo/clarify/secret requests → heads-up notifications with action buttons + in-sheet dialog (`*.request` events + `*.respond` methods).
- File browser sheet via REST `/api/fs/list` + `/api/fs/read-text`; preview sheet via WebView.
- Settings panes via `config.get`/`config.set` + `model.options` (provider/model switcher in Compose).
- Session list/switcher via `session.list` + `session.activate`; cron/profiles/skills read-only views.
- Previews: `/api/media` + image attachments (`image.attach`).

---

## Risks / tradeoffs / open questions

- **New persistent process on the server** (serve in a screen session) — needs Robbie's OK (house rule: ask before new always-running processes). It is the essence of Path 1; screen-supervised, not a daemon.
- **Unauthenticated on Tailscale** (`--insecure`) — acceptable on the private net; later hardening = gateway token/OAuth (desktop's auth path) behind `hermes gateway`.
- **Two transports in the app** during transition: relay (legacy) + gateway (new). UI switches by mode; relay code stays until v2 proves stable, then Phase-5-style delete.
- **serve spawns per-session agent children** (tui_gateway.entry) — same process model as desktop; idle sessions consume a child while open; `session.close` cleans up.
- Host-header middleware: phone must use `100.123.127.108` exactly (it will).
- Sheet rework risk: transcript scrolling inside a draggable sheet — watch gesture conflicts; verify PEEK/HALF/FULL gestures still work with the LazyColumn.
