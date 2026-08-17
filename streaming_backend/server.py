import os
import asyncio
import datetime
import json
import subprocess
from typing import Set
from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from pydantic import BaseModel
import edge_tts
import uuid

app = FastAPI()

VOICE = "en-US-AriaNeural"

# Connected phone clients — used to push unsolicited notifications
# (agent events from ANY running session on ANY host).
connected_clients: Set[WebSocket] = set()

# Pending notify events: if no phone is connected when an event fires
# (app closed, screen off, WS dropped), buffer it here and flush it to
# the phone on its next connection. Bounded to avoid unbounded growth.
MAX_PENDING = 20
pending_events: list = []


class HermesEvent(BaseModel):
    hook_event_name: str = ""
    tool_name: str | None = None
    tool_input: dict | None = None
    session_id: str = ""
    cwd: str = ""
    extra: dict = {}


class ChatMessageIn(BaseModel):
    message: str = ""
    session_id: str = ""


def _run_hermes(message: str, session: str) -> str:
    """Run one-shot hermes against the given session and return stdout."""
    env = os.environ.copy()
    env["PATH"] = f"/home/service/.local/bin:{env.get('PATH', '')}"
    cmd = ["hermes", "-z", message, "--continue", session]
    try:
        result = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            check=True,
            env=env,
            timeout=300,
        )
        return result.stdout.strip()
    except subprocess.CalledProcessError as e:
        print(f"Error: {e.stderr}")
        return "I'm sorry, I encountered an error."
    except FileNotFoundError:
        return "Hermes binary not found."
    except subprocess.TimeoutExpired:
        return "Hermes took too long to respond."


# ----------------------------------------------------------------------
# Live-session injection via tmux
#
# A persistent interactive session started with hermes-tmux.sh runs
# under a tmux session named hermes_<session_id>. When a reply targets
# that session and it is LIVE (in the liveness registry), we can type
# the message straight into the running TUI with `tmux send-keys` —
# the one real live-injection channel available. If the session is not
# live / not under tmux, we fall back to the one-shot `hermes -z`.
# ----------------------------------------------------------------------

def _tmux_bin() -> str:
    """Locate a terminal multiplexer for live-session injection.

    Prefers screen (system-installed on the server, no root needed);
    falls back to the locally-extracted tmux. Both expose the same
    pattern: name the session hermes_<session_id>, then inject text.
    """
    screen = "/usr/bin/screen"
    if os.path.exists(screen):
        return screen
    tmux_candidates = [
        os.path.expanduser("~/bin/tmux-local/usr/bin/tmux"),
        "/usr/bin/tmux",
    ]
    for c in tmux_candidates:
        if os.path.exists(c):
            return c
    return "tmux"


def _session_exists(session_id: str) -> bool:
    """True if a screen/tmux session named hermes_<session_id> exists."""
    mux = _tmux_bin()
    name = f"hermes_{session_id}"
    try:
        if os.path.basename(mux) == "screen":
            # screen -list lists attached/detached sessions; grep the name.
            out = subprocess.run(
                [mux, "-list"], capture_output=True, text=True, timeout=5
            ).stdout
            return f"{name}" in out
        has = subprocess.run(
            [mux, "has-session", "-t", name],
            capture_output=True, text=True, timeout=5,
        )
        return has.returncode == 0
    except Exception:
        return False


def _inject_via_tmux(session_id: str, message: str) -> str | None:
    """Type a message into the live screen/tmux session for the given id.

    Returns a short confirmation on success, or None when the session
    isn't running under a multiplexer we can reach.
    """
    mux = _tmux_bin()
    session_name = f"hermes_{session_id}"
    try:
        if not _session_exists(session_id):
            return None

        if os.path.basename(mux) == "screen":
            # screen has no separate 'Enter' key name — send the literal
            # carriage return. $'...' is not available in subprocess list
            # form, so pass the string with the control char embedded.
            subprocess.run(
                [mux, "-S", session_name, "-X", "stuff", message + "\r"],
                capture_output=True, text=True, timeout=10,
            )
        else:
            # tmux: 'Enter' is a key name.
            subprocess.run(
                [mux, "send-keys", "-t", session_name, message, "Enter"],
                capture_output=True, text=True, timeout=10,
            )
        print(f"⌨️ Injected into live {os.path.basename(mux)} session {session_name}: {message[:120]}")
        return "Delivered to live session"
    except Exception as e:
        print(f"inject failed: {e}")
        return None


def _chat_reply(message: str, session_id: str) -> tuple[str, bool]:
    """Route a message to a session: live-inject if possible, else one-shot.

    Returns (reply_text, injected_live).
    """
    if session_id and _session_exists(session_id):
        injected = _inject_via_tmux(session_id, message)
        if injected is not None:
            return injected, True
        # Live but not multiplexer-reachable — fall through to one-shot.
    return _run_hermes(message, session_id or f"android_{datetime.datetime.now().strftime('%Y_%m_%d')}"), False


async def send_to_phone(payload: dict) -> int:
    """Send a JSON payload to every connected phone, dropping stale sockets.

    Returns how many phones received it. Used for chat replies as well as
    notify events so a reply can't be lost when the app reconnects mid-think.
    """
    stale = []
    for ws in connected_clients:
        try:
            await ws.send_json(payload)
        except Exception:
            stale.append(ws)
    for ws in stale:
        connected_clients.discard(ws)
    return len(connected_clients)


async def send_to_phone_bytes(data: bytes) -> int:
    """Send raw binary (audio) to every connected phone, dropping stale sockets."""
    stale = []
    for ws in connected_clients:
        try:
            await ws.send_bytes(data)
        except Exception:
            stale.append(ws)
    for ws in stale:
        connected_clients.discard(ws)
    return len(connected_clients)


@app.post("/chat/message")
async def chat_message(msg: ChatMessageIn):
    """One-shot text message to a specific session.

    Used by the app's notification direct-reply (which must work even
    when the phone app process is not running — HTTP, not WebSocket).
    """
    message = msg.message.strip()
    if not message:
        return {"ok": False, "error": "empty message"}

    today = datetime.datetime.now().strftime("%Y_%m_%d")
    session_name = f"android_{today}"
    effective_session = msg.session_id or session_name

    reply, injected_live = _chat_reply(message, effective_session)
    print(f"💬 HTTP chat -> session {effective_session}: {reply[:200]}")
    return {
        "ok": True,
        "session_id": effective_session,
        "reply": reply,
        "injected_live": injected_live,
    }


@app.post("/hermes-events")
async def hermes_event(event: HermesEvent):
    """Receive shell-hook events from any Hermes host and relay them to the phone."""
    kind = "response"
    title = "Hermes finished a session"
    message = ""

    if event.hook_event_name == "post_tool_call" and event.tool_name == "clarify":
        kind = "question"
        title = "Hermes is waiting for your input"
        question = (event.tool_input or {}).get("question") or "The agent asked you a question"
        choices = (event.tool_input or {}).get("choices") or []
        message = question
        if choices:
            message += "  Options: " + " | ".join(str(c) for c in choices)
    elif event.hook_event_name == "pre_approval_request":
        kind = "approval"
        title = "Hermes needs your approval"
        message = (event.extra or {}).get("description") or (event.extra or {}).get("command") or "The agent is waiting for approval"
    else:
        # on_session_end and anything else
        title = "Hermes finished"
        # Prefer the actual response text (pulled by notify_hermes.py from
        # the local session DB) over a bare session id.
        message = (event.extra or {}).get("response_text") or (event.extra or {}).get("session_key") or event.session_id or "A session just completed"
        # Include the session title when we have one, so the phone can
        # show context ("Solar System Trivia Question") not just an id.
        session_title = (event.extra or {}).get("session_title") or ""
        if session_title:
            title = f"Hermes finished · {session_title}"

    payload = {
        "type": "notify",
        "kind": kind,
        "title": title,
        "message": message,
        "session_id": event.session_id,
        "event": event.hook_event_name,
        "host": (event.extra or {}).get("host", ""),
    }

    relayed = await send_to_phone(payload)

    # Buffer ONLY events that no phone received. If at least one phone got
    # it live, don't keep a copy — otherwise the next reconnect's
    # flush_pending would re-deliver an event the user already saw
    # (duplicate notifications, worse with flapping connections).
    if relayed == 0:
        pending_events.append(payload)
        if len(pending_events) > MAX_PENDING:
            pending_events.pop(0)

    print(f"📣 Relayed {kind} event to {relayed} phone(s): {title}")
    return {"ok": True, "relayed": relayed, "pending": len(pending_events)}


async def flush_pending(websocket: WebSocket) -> None:
    """Push any buffered events to a newly connected phone."""
    while pending_events:
        payload = pending_events.pop(0)
        try:
            await websocket.send_json(payload)
        except Exception:
            # Connection broke mid-flush — put it back
            pending_events.insert(0, payload)
            break


@app.websocket("/chat/stream")
async def websocket_endpoint(websocket: WebSocket):
    await websocket.accept()
    connected_clients.add(websocket)
    print(f"🔌 Phone connected ({len(connected_clients)} total)")

    # Deliver anything buffered while the phone was away
    await flush_pending(websocket)

    today = datetime.datetime.now().strftime("%Y_%m_%d")
    session_name = f"android_{today}"

    try:
        while True:
            # 1. Wait for Android to send the transcribed text.
            #    Plain text = normal voice chat (daily android session).
            #    JSON {"message": ..., "session_id": ...} = reply targeted
            #    at a specific Hermes session (e.g. answer a clarify prompt).
            data = await websocket.receive_text()
            print(f"🎙️ Received from Android: {data[:200]}")

            # 2. Tell Android we are thinking
            await websocket.send_json({"type": "status", "message": "Thinking..."})

            message = data
            target_session = None
            stripped = data.strip()
            if stripped.startswith("{"):
                try:
                    import json as _json
                    obj = _json.loads(stripped)
                    if isinstance(obj, dict) and obj.get("message"):
                        message = obj["message"]
                        target_session = obj.get("session_id") or None
                except Exception:
                    pass  # not JSON — treat as plain text

            # 3. Call Hermes on the server
            # Target session: explicit session_id wins; otherwise fall back
            # to the daily rotating android session.
            effective_session = target_session or session_name
            print(f"🎯 Session: {effective_session}")

            # Live sessions under tmux get the message typed straight into
            # the TUI (real live injection); everything else runs one-shot.
            reply_text, injected_live = await asyncio.to_thread(_chat_reply, message, effective_session)
            print(f"💬 Hermes says: {reply_text[:200]}")

            if injected_live:
                # The message went into the running session; the session's
                # own response will arrive via the notify hook when it
                # completes. Don't run a second one-shot reply.
                await send_to_phone({"type": "status", "message": "Sent to live session"})
                await send_to_phone({"type": "text", "message": reply_text})
                continue

            # Send the text to the phone. Broadcast to ALL connected phones
            # (dropping stale sockets) so a reply isn't lost if the app
            # reconnected while hermes was thinking.
            await send_to_phone({"type": "text", "message": reply_text})
            await send_to_phone({"type": "status", "message": "Speaking..."})

            # 4. Stream the Audio bytes as they are generated by Edge-TTS
            communicate = edge_tts.Communicate(reply_text, VOICE)

            # We stream the mp3 chunks over the websocket directly! No file saving needed.
            async for chunk in communicate.stream():
                if chunk["type"] == "audio":
                    # Send raw binary audio chunk
                    await send_to_phone_bytes(chunk["data"])

            # Tell Android we finished sending this audio stream
            await send_to_phone({"type": "audio_end"})

    except WebSocketDisconnect:
        print("Android disconnected.")
    finally:
        connected_clients.discard(websocket)


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
