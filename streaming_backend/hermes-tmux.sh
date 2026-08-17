#!/bin/bash
# hermes-tmux: launch an interactive hermes chat session inside tmux so
# the relay can inject replies into it later (tmux send-keys).
#
# Usage:
#   hermes-tmux [hermes chat args...]
#
# The tmux session is named hermes_<session_id> so the relay can find
# the live session by id:
#   - With --resume/-r SESSION_ID (or --continue NAME): the id is known
#     up front, so the tmux session is named immediately.
#   - Without an explicit id: the fresh session's id appears only after
#     the first exchange, so we watch the liveness registry
#     (~/.hermes/runtime/active_sessions.json) for a short while and
#     rename the tmux session when it shows up. If it never shows (plain
#     TUI doesn't register), the session keeps a temporary name and you
#     can rename it by hand later.

set -euo pipefail

export PATH="/home/service/.local/bin:$PATH"

# Resolve tmux (installed locally without root)
TMUX="$HOME/bin/tmux-local/usr/bin/tmux"
if [ ! -x "$TMUX" ]; then
    TMUX="$(command -v tmux || true)"
fi
if [ -z "$TMUX" ]; then
    echo "error: tmux not found" >&2
    exit 1
fi

# If inside a tmux already, refuse (would nest).
if [ -n "${TMUX:-}" ] && [ -n "${TMUX_PANE:-}" ]; then
    echo "error: already inside tmux — run this from a plain shell" >&2
    exit 1
fi

# Extract an explicit session id from --resume/-r if present.
SESSION_ID=""
ARGS=()
prev=""
for a in "$@"; do
    if [ "$prev" = "--resume" ] || [ "$prev" = "-r" ]; then
        SESSION_ID="$a"
    fi
    case "$a" in
        --resume=*|-r=*) SESSION_ID="${a#*=}" ;;
    esac
    ARGS+=("$a")
    prev="$a"
done

if [ -n "$SESSION_ID" ]; then
    TMUX_NAME="hermes_${SESSION_ID}"
    "$TMUX" new-session -d -s "$TMUX_NAME" "exec hermes chat $*"
    echo "hermes session $SESSION_ID running in tmux: $TMUX_NAME"
    exit 0
fi

# No explicit id: start with a temp name and try to rename from the
# liveness registry once the session registers.
TMP_NAME="hermes-starting-$$"
"$TMUX" new-session -d -s "$TMP_NAME" "exec hermes chat $*"

STATE="$HOME/.hermes/runtime/active_sessions.json"
DETECTED=""
for i in $(seq 1 30); do
    if [ -f "$STATE" ]; then
        DETECTED=$(python3 - "$STATE" <<'PY'
import json, sys
try:
    with open(sys.argv[1]) as fh:
        data = json.load(fh)
    entries = data.get("entries") or []
    if not entries:
        sys.exit(0)
    newest = max(entries, key=lambda e: int(e.get("pid") or 0))
    print(newest.get("session_id") or "")
except Exception:
    pass
PY
        )
        if [ -n "$DETECTED" ]; then
            break
        fi
    fi
    sleep 1
done

if [ -n "$DETECTED" ]; then
    "$TMUX" rename-session -t "$TMP_NAME" "hermes_${DETECTED}"
    echo "hermes session $DETECTED running in tmux: hermes_${DETECTED}"
else
    echo "warning: could not detect session id; tmux session: $TMP_NAME"
    echo "To target it, rename: tmux rename-session -t $TMP_NAME hermes_<session_id>"
fi
