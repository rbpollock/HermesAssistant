#!/bin/bash
# hermes-tmux: launch an interactive hermes chat session inside a
# terminal multiplexer (screen, or tmux as fallback) so the relay can
# inject replies into it later (screen -X stuff / tmux send-keys).
#
# Usage:
#   hermes-tmux [hermes chat args...]
#
# The multiplexer session is named hermes_<session_id> so the relay can
# find the live session by id:
#   - With --resume/-r SESSION_ID (or --continue NAME): the id is known
#     up front, so the session is named immediately.
#   - Without an explicit id: the fresh session's id appears only after
#     the first exchange, so we watch the liveness registry
#     (~/.hermes/runtime/active_sessions.json) for a short while and
#     rename the session when it shows up. If it never shows (plain
#     TUI doesn't register), the session keeps a temporary name and you
#     can rename it by hand later.

set -euo pipefail

export PATH="/home/service/.local/bin:$PATH"

# Pick a multiplexer: prefer screen (system-installed), fall back to
# the locally-extracted tmux.
MUX=""
if command -v screen >/dev/null 2>&1; then
    MUX="screen"
elif [ -x "$HOME/bin/tmux-local/usr/bin/tmux" ]; then
    MUX="$HOME/bin/tmux-local/usr/bin/tmux"
elif command -v tmux >/dev/null 2>&1; then
    MUX="tmux"
else
    echo "error: neither screen nor tmux found" >&2
    exit 1
fi

# If inside a screen/tmux already, refuse (would nest).
if [ -n "${STY:-}" ] || [ -n "${TMUX:-}" ]; then
    echo "error: already inside a multiplexer — run this from a plain shell" >&2
    exit 1
fi

mux_launch() { # $1 = session name, rest = command string (already quoted)
    local name="$1"; shift
    if [ "$MUX" = "screen" ]; then
        # screen needs the command as separate args; wrapping in sh -c
        # is the reliable form for detached sessions.
        screen -dmS "$name" sh -c "$*"
    else
        "$MUX" new-session -d -s "$name" "$*"
    fi
}

mux_rename() { # $1 = old name, $2 = new name
    if [ "$MUX" = "screen" ]; then
        # screen has no rename: restart under the right name instead.
        # (Only used when no explicit id was given; rare path.)
        echo "screen cannot rename sessions; keeping $1" >&2
    else
        "$MUX" rename-session -t "$1" "$2"
    fi
}

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
    mux_launch "$TMUX_NAME" "exec hermes chat $*"
    echo "hermes session $SESSION_ID running in $MUX: $TMUX_NAME"
    exit 0
fi

# No explicit id: start with a temp name and try to rename from the
# liveness registry once the session registers.
TMP_NAME="hermes-starting-$$"
mux_launch "$TMP_NAME" "exec hermes chat $*"

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
    mux_rename "$TMP_NAME" "hermes_${DETECTED}"
    echo "hermes session $DETECTED running in $MUX: hermes_${DETECTED}"
else
    echo "warning: could not detect session id; $MUX session: $TMP_NAME"
    echo "To target it, start with: hermes-tmux --resume <session_id>"
fi
