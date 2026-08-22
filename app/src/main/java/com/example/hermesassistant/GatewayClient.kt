package com.example.hermesassistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** JSON-RPC error from the gateway (code + message from the error object). */
class GatewayException(val code: Int, message: String) : Exception(message)

/**
 * JSON-RPC/WebSocket transport for the Hermes tui_gateway (/api/ws), a port
 * of the desktop app's JsonRpcGatewayClient semantics:
 *
 *   - requests: {id, method, params} -> {id, result} / {id, error}
 *   - events:   {method:"event", params:{type, payload, session_id}}
 *   - connect timeout 15s, request timeout 120s, ping every 15s
 *   - read timeout DISABLED (the 10s OkHttp trap — streaming turns can idle)
 *   - a fresh single-use auth ticket is minted before every connect
 *   - exponential reconnect backoff (2s..30s) unless disconnected manually
 *
 * All listener callbacks are posted on the main thread (UI may update
 * directly, same convention as RelayClient).
 */
class GatewayClient(
    private val context: Context,
    private val auth: GatewayAuth,
    private val scope: CoroutineScope,
) {
    enum class State { IDLE, CONNECTING, OPEN, CLOSED, ERROR }

    interface Listener {
        fun onStateChanged(state: State)
        fun onEvent(type: String, payload: JSONObject?, sessionId: String?)
        fun onFatal(message: String)
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // the 10s trap — never cut a streaming turn
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
    private val nextId = AtomicInteger(0)

    private var webSocket: WebSocket? = null
    private var listener: Listener? = null
    private var manualClose = false
    private var reconnectAttempts = 0
    private var reconnectRunnable: Runnable? = null

    fun attach(listener: Listener) {
        this.listener = listener
    }

    fun connect() {
        manualClose = false
        if (_state.value == State.CONNECTING || _state.value == State.OPEN) return
        setState(State.CONNECTING)
        scope.launch {
            val ticket = try {
                withContext(Dispatchers.IO) { auth.mintTicket() }
            } catch (e: Exception) {
                null
            }
            if (ticket.isNullOrEmpty()) {
                setState(State.ERROR)
                main.post {
                    listener?.onFatal("Gateway auth failed — check gateway credentials in Settings")
                }
                scheduleReconnect()
                return@launch
            }
            openSocket("${ServerConfig.gatewayWsBase(context)}/api/ws?ticket=$ticket")
        }
    }

    fun disconnect() {
        manualClose = true
        stopReconnectLoop()
        webSocket?.close(1000, "bye")
        webSocket = null
        failAllPending("gateway disconnected")
        setState(State.IDLE)
    }

    /** JSON-RPC request; resolves with the result object or throws. */
    suspend fun request(method: String, params: JSONObject = JSONObject()): JSONObject {
        if (_state.value != State.OPEN) throw GatewayException(-1, "Gateway not connected")
        val id = nextId.incrementAndGet()
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        val frame = JSONObject()
            .put("id", id)
            .put("method", method)
            .put("params", params)
        if (webSocket?.send(frame.toString()) != true) {
            pending.remove(id)
            throw GatewayException(-1, "Gateway send failed (not connected)")
        }
        return try {
            withTimeout(REQUEST_TIMEOUT_MS) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            pending.remove(id)
            throw GatewayException(-1, "Gateway request timed out: $method")
        } catch (e: CancellationException) {
            pending.remove(id)
            throw e
        }
    }

    private fun openSocket(url: String) {
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectAttempts = 0
                stopReconnectLoop()
                setState(State.OPEN)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleFrame(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                setState(State.CLOSED)
                failAllPending("gateway closed")
                if (!manualClose) scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                setState(State.ERROR)
                failAllPending("gateway failure: ${t.message}")
                if (!manualClose) scheduleReconnect()
            }
        })
    }

    /** Mirrors apps/shared json-rpc-gateway.ts handleMessage dispatch. */
    private fun handleFrame(text: String) {
        try {
            val frame = JSONObject(text)
            if (!frame.isNull("id")) {
                val id = frame.getInt("id")
                val deferred = pending.remove(id) ?: return
                if (frame.has("error")) {
                    val err = frame.optJSONObject("error")
                    deferred.completeExceptionally(
                        GatewayException(
                            err?.optInt("code", 0) ?: 0,
                            err?.optString("message", "Hermes RPC failed") ?: "Hermes RPC failed",
                        )
                    )
                } else {
                    deferred.complete(frame.optJSONObject("result") ?: JSONObject())
                }
                return
            }
            if (frame.optString("method") == "event") {
                val params = frame.optJSONObject("params") ?: return
                val type = params.optString("type")
                if (type.isEmpty()) return
                val payload = params.optJSONObject("payload")
                val sessionId = params.optString("session_id", "")
                main.post { listener?.onEvent(type, payload, sessionId) }
            }
        } catch (e: Exception) {
            // Malformed frame — never kill the socket.
        }
    }

    private fun setState(s: State) {
        _state.value = s
        main.post { listener?.onStateChanged(s) }
    }

    private fun failAllPending(reason: String) {
        val keys = pending.keys.toList()
        keys.forEach { k ->
            pending.remove(k)?.completeExceptionally(GatewayException(-1, reason))
        }
    }

    /** Exponential-ish reconnect: 2s, 4s, 8s... capped at 30s. */
    private fun scheduleReconnect() {
        if (reconnectRunnable != null) return // already scheduled
        val delay = minOf(30_000L, 2_000L shl minOf(reconnectAttempts, 4))
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

    companion object {
        private const val REQUEST_TIMEOUT_MS = 120_000L
    }
}
