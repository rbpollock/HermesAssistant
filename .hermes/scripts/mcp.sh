#!/bin/bash
# MCP helper: call android-remote-control-mcp tools via streamable HTTP.
# Usage: mcp.sh <method> [json-params]
# Session is maintained in /tmp/mcp_session.txt (re-initializes if stale).
MCP_URL="http://100.117.180.81:8080/mcp"
SESSION_FILE="/tmp/mcp_session.txt"
ID_FILE="/tmp/mcp_id.txt"

METHOD="$1"
PARAMS="${2:-{}}"
shift 2>/dev/null || true

# Get or refresh session id
if [ ! -f "$SESSION_FILE" ]; then
  RESP=$(curl -sS -m 15 -D - -X POST "$MCP_URL" \
    -H "Content-Type: application/json" \
    -H "Accept: application/json, text/event-stream" \
    -d "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},\"clientInfo\":{\"name\":\"audit-client\",\"version\":\"1.0\"}}}" )
  SID=$(echo "$RESP" | grep -i '^mcp-session-id:' | tr -d '\r' | awk '{print $2}')
  echo "$SID" > "$SESSION_FILE"
fi
SID=$(cat "$SESSION_FILE")

# Increment id
ID=1
[ -f "$ID_FILE" ] && ID=$(($(cat "$ID_FILE") + 1))
echo "$ID" > "$ID_FILE"

# tools/call uses a nested structure
if [ "$METHOD" = "tools/call" ]; then
  BODY="{\"jsonrpc\":\"2.0\",\"id\":$ID,\"method\":\"$METHOD\",\"params\":{\"name\":\"$PARAMS\",\"arguments\":$3}}"
else
  BODY="{\"jsonrpc\":\"2.0\",\"id\":$ID,\"method\":\"$METHOD\",\"params\":$PARAMS}"
fi

curl -sS -m 60 -X POST "$MCP_URL" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "mcp-session-id: $SID" \
  -d "$BODY"
