package com.example.hermesassistant

import org.json.JSONObject

/**
 * Typed calls over the gateway transport. Method names + payloads verified
 * against tui_gateway/server.py on irl-server-01 (v0.20.4).
 */
class GatewayApi(private val client: GatewayClient) {

    /** Create a fresh gateway session; returns the 8-hex session id. */
    suspend fun createSession(): String =
        client.request("session.create").optString("session_id", "")

    /** Submit a prompt to a session; the reply streams back as events. */
    suspend fun submit(sessionId: String, text: String) {
        client.request(
            "prompt.submit",
            JSONObject()
                .put("session_id", sessionId)
                .put("text", text),
        )
    }

    suspend fun interrupt(sessionId: String) {
        client.request("session.interrupt", JSONObject().put("session_id", sessionId))
    }

    suspend fun close(sessionId: String) {
        client.request("session.close", JSONObject().put("session_id", sessionId))
    }
}
