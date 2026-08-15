#!/usr/bin/env python3
"""
Hermes shell-hook notifier — relays agent lifecycle events to the
HermesAssistant streaming server, which pushes them to the phone.

Installed on every host that runs Hermes sessions the user wants to
hear about. Reads the hook payload from stdin (JSON, documented in
agent/shell_hooks.py) and POSTs it to the relay endpoint.

Fire-and-forget: any failure is swallowed so the hook NEVER blocks
or delays the agent.
"""
import json
import os
import sqlite3
import sys
import urllib.request

# The relay server (irl-server-01 over Tailscale). Accepts overrides.
RELAY_URL = os.environ.get(
    "HERMES_NOTIFY_RELAY", "http://100.123.127.108:8000/hermes-events"
)

# Which events do we care about? Everything else is dropped at the source.
INTERESTING_EVENTS = {
    "on_session_end",
    "post_tool_call",       # filtered to clarify below
    "pre_approval_request",
}

# cwd prefixes to treat as "the phone's own conversation" — the relay
# server spawns `hermes -z --continue android_*` from its own directory
# for every phone voice command. Those already get their answer over the
# WebSocket; echoing them back as a "Hermes finished" notification is
# noise. Filter at the source so the relay stays dumb.
SELF_CWD_PREFIXES = {
    "/home/service/streaming_backend",
}


def _session_db_path():
    """Locate the Hermes session DB for this host."""
    hermes_home = os.environ.get("HERMES_HOME", "")
    candidates = []
    if hermes_home:
        candidates.append(os.path.join(hermes_home, "state.db"))
    candidates += [
        os.path.expanduser("~/.hermes/state.db"),
        os.path.expanduser("~/AppData/Local/hermes/state.db"),
    ]
    for c in candidates:
        if os.path.exists(c):
            return c
    return None


def _last_assistant_text(session_id: str) -> str:
    """Fetch the most recent assistant message for a session, if any.

    This is what makes notifications useful: instead of just a session id,
    the phone can show the actual text Hermes just produced. Best-effort —
    a missing DB or session simply yields "" (caller falls back to the id).
    """
    if not session_id:
        return ""
    db_path = _session_db_path()
    if not db_path:
        return ""
    try:
        conn = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True, timeout=3)
        try:
            cur = conn.cursor()
            cur.execute(
                "SELECT content FROM messages "
                "WHERE session_id = ? AND role = 'assistant' AND content IS NOT NULL "
                "ORDER BY id DESC LIMIT 1",
                (session_id,),
            )
            row = cur.fetchone()
            return (row[0] or "").strip() if row else ""
        finally:
            conn.close()
    except Exception:
        return ""


def _session_title(session_id: str) -> str:
    """Fetch the human-readable title for a session, if any."""
    if not session_id:
        return ""
    db_path = _session_db_path()
    if not db_path:
        return ""
    try:
        conn = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True, timeout=3)
        try:
            cur = conn.cursor()
            cur.execute(
                "SELECT title FROM sessions WHERE id = ?",
                (session_id,),
            )
            row = cur.fetchone()
            return (row[0] or "").strip() if row else ""
        finally:
            conn.close()
    except Exception:
        return ""


def main() -> None:
    try:
        payload = json.load(sys.stdin)
    except Exception:
        return

    event = payload.get("hook_event_name", "")

    if event not in INTERESTING_EVENTS:
        return

    # Only relay clarify tool calls (agent asking the user a question).
    if event == "post_tool_call" and payload.get("tool_name") != "clarify":
        return

    # Drop the phone's own one-shot sessions (see SELF_CWD_PREFIXES).
    cwd = payload.get("cwd", "") or ""
    if event == "on_session_end" and any(
        cwd.startswith(p) for p in SELF_CWD_PREFIXES
    ):
        return

    # Tag with hostname so the phone can say which machine it came from.
    payload.setdefault("extra", {})
    payload["extra"]["host"] = os.uname().nodename if hasattr(os, "uname") else "windows"

    # Feature: attach the actual response text and session title for
    # session-end events so the phone notification has context, not just
    # a session id. Cap generously (4000 chars): enough for any normal
    # Hermes reply without dumping a giant transcript into the phone's
    # notification. (The app and relay never truncate — this is the only
    # place a message can get cut, so keep it generous.)
    if event == "on_session_end":
        session_id = payload.get("session_id") or ""
        text = _last_assistant_text(session_id)
        if text:
            payload["extra"]["response_text"] = text[:4000]
        title = _session_title(session_id)
        if title:
            payload["extra"]["session_title"] = title[:200]

    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        RELAY_URL,
        data=data,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=5) as resp:
            resp.read()
    except Exception:
        # Never block the agent pipeline.
        pass


if __name__ == "__main__":
    main()
