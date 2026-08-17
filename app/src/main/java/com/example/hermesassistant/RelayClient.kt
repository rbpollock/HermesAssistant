package com.example.hermesassistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Owns the WebSocket connection to the relay server: connect/reconnect
 * lifecycle, outbound send, and inbound message dispatch.
 *
 * All listener callbacks are delivered on the main thread (the WS
 * listener fires on OkHttp background threads; we hop to the main
 * looper so the UI can update directly).
 */
class RelayClient(private val context: Context) {

    interface Listener {
        fun onConnected()
        fun onDisconnected(reason: String)
        fun onTextResponse(message: String)
        fun onStatus(message: String)
        fun onNotify(json: JSONObject)
        fun onAudioBytes(bytes: ByteString)
        fun onAudioEnd()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    private val main = Handler(Looper.getMainLooper())
    private val reconnectHandler = Handler(Looper.getMainLooper())

    private var webSocket: WebSocket? = null
    private var reconnectRunnable: Runnable? = null
    private var reconnectAttempts = 0

    @Volatile
    var isConnected = false
        private set

    @Volatile
    private var isConnecting = false

    private var listener: Listener? = null

    fun attach(listener: Listener) {
        this.listener = listener
    }

    /** Connect (or no-op if already connected/connecting). Idempotent. */
    fun connect() {
        if (isConnected || isConnecting) return
        isConnecting = true
        webSocket?.cancel()

        val request = Request.Builder()
            .url("${ServerConfig.wsBase(context)}/chat/stream")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                isConnecting = false
                reconnectAttempts = 0
                stopReconnectLoop()
                main.post { listener?.onConnected() }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
                isConnecting = false
                main.post { listener?.onDisconnected("Connection closed — reconnecting...") }
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                isConnecting = false
                main.post { listener?.onDisconnected("WS Error: ${t.message} — retrying...") }
                scheduleReconnect()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                main.post { dispatchText(text) }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                main.post { listener?.onAudioBytes(bytes) }
            }
        })
    }

    /** Parse an inbound JSON frame and dispatch to the listener. */
    private fun dispatchText(text: String) {
        try {
            val json = JSONObject(text)
            when (json.optString("type")) {
                "text" -> listener?.onTextResponse(json.optString("message"))
                "status" -> listener?.onStatus(json.optString("message"))
                "audio_end" -> listener?.onAudioEnd()
                "notify" -> listener?.onNotify(json)
                // Unknown types are ignored for forward compatibility.
            }
        } catch (e: Exception) {
            // A malformed frame must not kill the connection. The caller
            // gets the raw text via onStatus so it's visible, not silent.
            listener?.onStatus("⚠ WS frame: ${e.javaClass.simpleName}: ${e.message?.take(120)}")
        }
    }

    /** Send a text frame. Returns true when actually handed to the socket. */
    fun send(payload: String): Boolean =
        isConnected && webSocket?.send(payload) == true

    /** Tear down the connection and stop reconnect timers. Idempotent. */
    fun cancel() {
        stopReconnectLoop()
        webSocket?.cancel()
        webSocket = null
        isConnected = false
        isConnecting = false
    }

    /** Exponential-ish reconnect: 2s, 4s, 8s... capped at 30s. */
    private fun scheduleReconnect() {
        if (reconnectRunnable != null) return // already scheduled
        val delay = minOf(30000L, 2000L shl minOf(reconnectAttempts, 4))
        reconnectAttempts++
        val runnable = Runnable {
            reconnectRunnable = null
            connect()
        }
        reconnectRunnable = runnable
        reconnectHandler.postDelayed(runnable, delay)
    }

    private fun stopReconnectLoop() {
        reconnectRunnable?.let { reconnectHandler.removeCallbacks(it) }
        reconnectRunnable = null
        reconnectAttempts = 0
    }
}
