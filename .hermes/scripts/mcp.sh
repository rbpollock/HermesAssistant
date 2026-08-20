#!/bin/bash
# MCP helper with auth: call android-remote-control-mcp tools.
# Token comes from $MCP_TOKEN (set in the terminal env, never stored here).
# Usage: mcp.sh <method> [params-json]
#   tools/call: mcp.sh tools/call <tool-name> <args-json>
MCP_URL="http://100.117.180.81:8080/mcp"
SESSION_FILE="/tmp/mcp_session.txt"
ID_FILE="/tmp/mcp_id.txt"

METHOD="$1"
if [ "$METHOD" = "tools/call" ]; then
  NAME="$2"; ARGS="${3:-{}}"
else
  PARAMS="${2:-{}}"
fi

# Establish session if needed
if [ ! -f "$SESSION_FILE" ]; then
  RESP=$(curl -sS -m 20 -D - -X POST "$MCP_URL" \
    -H "Content-Type: application/json" \
    -H "Accept: application/json, text/event-stream" \
    -H "Authorization: Bearer ${MCP_TOKEN}" \
    -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"audit-client","version":"1.0"}}}' )
  SID=$(echo "$RESP" | grep -i '^mcp-session-id:' | tr -d '\r' | awk '{print $2}')
  echo "$SID" > "$SESSION_FILE"
fi
SID=$(cat "$SESSION_FILE")

ID=1; [ -f "$ID_FILE" ] && ID=$(($(cat "$ID_FILE") + 1)); echo "$ID" > "$ID_FILE"

if [ "$METHOD" = "tools/call" ]; then
  BODY="{\"jsonrpc\":\"2.0\",\"id\":$ID,\"method\":\"tools/call\",\"params\":{\"name\":\"$NAME\",\"arguments\":$ARGS}"
else
  BODY="{\"jsonrpc\":\"2.0\",\"id\":$ID,\"method\":\"$METHOD\",\"params\":$PARAMS}"
fi

curl -sS -m 90 -X POST "$MCP_URL" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -H "Authorization: Bearer ${MCP_TOKEN}" \
  -H "mcp-session-id: $SID" \
  -d "$BODY"
