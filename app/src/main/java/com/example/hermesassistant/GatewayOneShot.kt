package com.example.hermesassistant

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * One-shot gateway submit for the notification reply receiver, which runs
 * in a possibly cold-started process with no ViewModel: mint a ticket,
 * open a short-lived WS, prompt.submit, wait for message.complete, close.
 *
 * Returns the reply text, or null when the connection dropped without a
 * completion. Throws [GatewayException] on a JSON-RPC error frame.
 */
object GatewayOneShot {

    fun submit(wsBase: String, ticket: String, sessionId: String, text: String): String? {
        var reply: String? = null
        var errorMsg: String? = null
        val latch = CountDownLatch(1)

        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build()

        val ws = client.newWebSocket(
            Request.Builder().url("$wsBase/api/ws?ticket=$ticket").build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(
                        JSONObject()
                            .put("id", 1)
                            .put("method", "prompt.submit")
                            .put(
                                "params",
                                JSONObject().put("session_id", sessionId).put("text", text),
                            )
                            .toString()
                    )
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val frame = JSONObject(text)
                        if (!frame.isNull("id")) {
                            if (frame.has("error")) {
                                errorMsg = frame.optJSONObject("error")
                                    ?.optString("message", "gateway error")
                                    ?: "gateway error"
                            }
                            latch.countDown()
                            return
                        }
                        if (frame.optString("method") == "event") {
                            val params = frame.optJSONObject("params") ?: return
                            if (params.optString("type") == "message.complete") {
                                reply = params.optJSONObject("payload")?.optString("text")
                                webSocket.close(1000, "done")
                                latch.countDown()
                            }
                        }
                    } catch (e: Exception) {
                        // ignore malformed frames
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    latch.countDown()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    latch.countDown()
                }
            }
        )

        try {
            if (!latch.await(150, TimeUnit.SECONDS)) {
                return null
            }
            if (errorMsg != null) {
                throw GatewayException(0, errorMsg ?: "gateway error")
            }
            return reply
        } finally {
            ws.close(1000, "done")
            client.dispatcher.executorService.shutdown()
        }
    }
}
