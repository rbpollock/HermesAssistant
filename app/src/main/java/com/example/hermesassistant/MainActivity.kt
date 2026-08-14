package com.example.hermesassistant

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

import okio.ByteString

import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener as VoskRecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

class MainActivity : AppCompatActivity(), VoskRecognitionListener {

    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var statusText: TextView
    private lateinit var subText: TextView
    private lateinit var speakButton: Button
    private lateinit var statusRing: StatusRingView
    private lateinit var historyList: LinearLayout
    private lateinit var historyScroll: ScrollView
    private lateinit var notificationManager: NotificationManager
    private var tts: TextToSpeech? = null
    private lateinit var chatHistory: ChatHistoryStore

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS) // Generous connect timeout for Tailscale
        .readTimeout(0, TimeUnit.MILLISECONDS) // No read timeout for long LLM responses
        .pingInterval(15, TimeUnit.SECONDS) // Keep Tailscale NAT alive
        .retryOnConnectionFailure(true)
        .build()
    private var webSocket: WebSocket? = null
    private var isConnected = false
    private var pendingMessage: String? = null
    private var mediaPlayer: MediaPlayer? = null

    // Auto-reconnect loop: when the socket dies (phone sleeps, Tailscale
    // blips, network switch) keep trying every few seconds instead of
    // showing a permanent "WS Error" and sitting dead.
    private val reconnectHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var reconnectRunnable: Runnable? = null
    private var reconnectAttempts = 0
    private var isConnecting = false

    // For streaming audio chunks
    private var audioTempFile: File? = null
    private var audioOutputStream: FileOutputStream? = null

    private var voskModel: Model? = null
    private var voskService: SpeechService? = null

    // Guards against duplicate auto-listen triggers
    private var autoListenScheduled = false

    // Offline dictation mode: Vosk captures a full phrase instead of
    // scanning for the wake word, and the text goes to the offline queue.
    private var dictationMode = false
    private var dictationWatchdog: Runnable? = null

    // True while we are flushing the offline queue after reconnect
    private var flushingQueue = false

    // Targeted-reply state: set when a notify event carries a session_id,
    // so the next spoken message is routed back to that session
    // (answering a clarify prompt or approval request).
    private var replySessionId: String = ""
    private var replySessionTitle: String = ""

    companion object {
        private const val NOTIFICATION_CHANNEL = "hermes_events"
        private const val NOTIFICATION_ID = 42
        private const val REQ_POST_NOTIFICATIONS = 2
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        subText = findViewById(R.id.subText)
        speakButton = findViewById(R.id.speakButton)
        statusRing = findViewById(R.id.statusRing)
        historyList = findViewById(R.id.historyList)
        historyScroll = findViewById(R.id.historyScroll)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        chatHistory = ChatHistoryStore(this)

        createNotificationChannel()
        initTts()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        requestNotificationPermissionIfNeeded()

        setupSpeechRecognizer()
        connectWebSocket()
        initVosk()
        renderHistory()
        updateQueueBadge()

        speakButton.setOnClickListener {
            if (dictationMode) {
                // Tap again while dictating = cancel
                exitDictationMode()
            } else if (!isConnected) {
                // Offline: transcribe locally with Vosk and queue the message
                startOfflineDictation()
            } else if (mediaPlayer == null && audioTempFile != null && !isSpeaking) {
                // A response is waiting (no Bluetooth autoplay happened) — tap plays it
                playPendingAudio()
            } else {
                startListening()
            }
        }

        // Auto-start listening if invoked via the OS Assistant hardware button
        // or via our custom action from the VoiceInteractionSession
        if (intent?.action == Intent.ACTION_ASSIST || intent?.action == "com.example.hermesassistant.START_LISTENING") {
            if (isConnected) startListening() else startOfflineDictation()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL,
                "Hermes Agent Events",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when Hermes finishes a session or needs your input"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_POST_NOTIFICATIONS)
        }
    }

    // ------------------------------------------------------------------
    // Vosk wake word + offline dictation
    // ------------------------------------------------------------------

    private fun initVosk() {
        StorageService.unpack(this, "model", "model",
            { model: Model ->
                voskModel = model
                startVoskWakeWord()
            },
            { exception ->
                runOnUiThread { setStatus("Failed to load wake word model: ${exception.message}", StatusRingView.State.IDLE) }
            }
        )
    }

    private fun startVoskWakeWord() {
        if (voskModel == null) return
        try {
            val recognizer = Recognizer(voskModel, 16000.0f)
            voskService = SpeechService(recognizer, 16000.0f)
            voskService?.startListening(this)
            runOnUiThread {
                speakButton.text = "LISTENING FOR WAKE WORD"
                setStatus("Listening for \"Hey Hermes\"", StatusRingView.State.IDLE)
            }
        } catch (e: Exception) {
            runOnUiThread { setStatus("Vosk Error: ${e.message}", StatusRingView.State.IDLE) }
        }
    }

    /** Offline: Vosk captures the next full phrase and queues it. */
    private fun startOfflineDictation() {
        // Make sure Vosk is actually running (it doubles as the dictation engine)
        if (voskService == null) {
            startVoskWakeWord()
        }
        dictationMode = true
        speakButton.text = "TAP TO CANCEL"
        setStatus("Listening (offline — will queue)", StatusRingView.State.LISTENING)

        // Keep trying to reach the server in the background so queued
        // messages flush as soon as the link returns.
        connectWebSocket()

        // If the user says nothing, fall back to wake-word mode after 15s
        dictationWatchdog?.let { statusText.removeCallbacks(it) }
        dictationWatchdog = Runnable {
            if (dictationMode) {
                exitDictationMode()
            }
        }.also { statusText.postDelayed(it, 15000) }
    }

    private fun exitDictationMode() {
        dictationMode = false
        dictationWatchdog?.let { statusText.removeCallbacks(it) }
        dictationWatchdog = null
        speakButton.text = "LISTENING FOR WAKE WORD"
        setStatus("Listening for \"Hey Hermes\"", StatusRingView.State.IDLE)
        // Vosk keeps listening — restart the service so the recognizer
        // starts a fresh phrase-buffer in wake-word mode
        voskService?.stop()
        voskService = null
        startVoskWakeWord()
    }

    override fun onPartialResult(hypothesis: String?) {
        if (dictationMode) return // wait for the final phrase
        if (hypothesis?.contains("hey hermes") == true || hypothesis?.contains("hermes") == true) {
            triggerAssistantFromWakeWord()
        }
    }

    override fun onResult(hypothesis: String?) {
        if (dictationMode) {
            handleDictatedText(hypothesis)
            return
        }
        if (hypothesis?.contains("hey hermes") == true || hypothesis?.contains("hermes") == true) {
            triggerAssistantFromWakeWord()
        }
    }

    override fun onFinalResult(hypothesis: String?) {}

    override fun onError(exception: Exception?) {
        if (dictationMode) {
            exitDictationMode()
        }
    }

    override fun onTimeout() {
        if (dictationMode) {
            exitDictationMode()
        }
    }

    /** A full phrase was captured by Vosk while offline — queue it. */
    private fun handleDictatedText(hypothesis: String?) {
        val text = (hypothesis ?: "").trim()
        dictationMode = false
        dictationWatchdog?.let { statusText.removeCallbacks(it) }
        dictationWatchdog = null

        if (text.isNotEmpty()) {
            // Strip a leading wake word if the model caught it
            val clean = text
                .replaceFirst("(?i)^(hey )?hermes[\\s,.-]*".toRegex(), "")
                .trim()
            if (clean.isNotEmpty()) {
                chatHistory.enqueue(clean)
                renderHistory()
                updateQueueBadge()
                setStatus("Offline — queued: $clean", StatusRingView.State.CONNECTED)
            }
        }

        // Keep trying to reach the server so the queue flushes
        connectWebSocket()

        // Back to wake-word listening
        voskService?.stop()
        voskService = null
        startVoskWakeWord()
    }

    private fun triggerAssistantFromWakeWord() {
        // Pause Vosk so the main recognizer / dictation can take over
        voskService?.stop()
        voskService = null

        runOnUiThread {
            speakButton.text = "TAP TO SPEAK TO HERMES"
            if (isConnected) startListening() else startOfflineDictation()
        }
    }

    // ------------------------------------------------------------------
    // WebSocket connection
    // ------------------------------------------------------------------

    private fun connectWebSocket() {
        if (isConnected || isConnecting) return

        isConnecting = true
        webSocket?.cancel()

        val request = Request.Builder()
            .url("ws://100.123.127.108:8000/chat/stream")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                isConnecting = false
                reconnectAttempts = 0
                stopReconnectLoop()
                runOnUiThread {
                    setStatus("Connected to server", StatusRingView.State.CONNECTED)
                    pendingMessage?.let {
                        webSocket.send(it)
                        pendingMessage = null
                    }
                    // Feature 4: flush anything queued while offline
                    flushQueueIfAny()
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
                isConnecting = false
                runOnUiThread { setStatus("Connection closed — reconnecting...", StatusRingView.State.IDLE) }
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                isConnecting = false
                runOnUiThread {
                    setStatus("WS Error: ${t.message} — retrying...", StatusRingView.State.IDLE)
                }
                scheduleReconnect()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runOnUiThread {
                    try {
                        val json = JSONObject(text)
                        val type = json.optString("type")
                        when (type) {
                            "text" -> {
                                setStatus("Hermes: ${json.optString("message")}", StatusRingView.State.CONNECTED)
                                chatHistory.append(ChatMessage("hermes", json.optString("message")))
                                renderHistory()
                            }
                            "status" -> {
                                setStatus(json.optString("message"), StatusRingView.State.THINKING)
                            }
                            "audio_end" -> {
                                playAudioStream()
                                // If flushing queued messages, send the next one now
                                if (flushingQueue) sendNextQueued()
                            }
                            "notify" -> {
                                handleNotify(json)
                            }
                        }
                    } catch (e: Exception) {
                        setStatus("Error parsing: ${e.message}", StatusRingView.State.CONNECTED)
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Receive streaming raw MP3 byte chunks
                if (audioOutputStream == null) {
                    audioTempFile = File(cacheDir, "stream_${System.currentTimeMillis()}.mp3")
                    audioOutputStream = FileOutputStream(audioTempFile)
                }
                try {
                    audioOutputStream?.write(bytes.toByteArray())
                } catch (e: IOException) {
                    // ignore
                }
            }
        })
    }

    /** Exponential-ish reconnect: 2s, 4s, 8s... capped at 30s. */
    private fun scheduleReconnect() {
        if (reconnectRunnable != null) return // already scheduled
        val delay = minOf(30000L, 2000L shl minOf(reconnectAttempts, 4))
        reconnectAttempts++
        val runnable = Runnable {
            reconnectRunnable = null
            connectWebSocket()
        }
        reconnectRunnable = runnable
        reconnectHandler.postDelayed(runnable, delay)
    }

    private fun stopReconnectLoop() {
        reconnectRunnable?.let { reconnectHandler.removeCallbacks(it) }
        reconnectRunnable = null
        reconnectAttempts = 0
    }

    // ------------------------------------------------------------------
    // Offline queue flush
    // ------------------------------------------------------------------

    private fun flushQueueIfAny() {
        if (chatHistory.queue.isEmpty()) return
        flushingQueue = true
        sendNextQueued()
    }

    private fun sendNextQueued() {
        val next = chatHistory.popQueued() ?: run {
            flushingQueue = false
            updateQueueBadge()
            setStatus("All queued messages sent", StatusRingView.State.CONNECTED)
            return
        }
        chatHistory.markQueuedDelivered(next)
        renderHistory()
        updateQueueBadge()
        setStatus("Sending queued: ${next.text}", StatusRingView.State.THINKING)
        if (isConnected && webSocket?.send(next.text) == true) {
            // Response will arrive via onMessage; audio_end triggers the next send
        } else {
            // Send failed — put it back at the front and try again later
            flushingQueue = false
            chatHistory.requeue(next)
            renderHistory()
            updateQueueBadge()
            setStatus("Reconnect lost — message re-queued", StatusRingView.State.IDLE)
            connectWebSocket()
        }
    }

    // ------------------------------------------------------------------
    // Notify events from ANY Hermes session (shell hooks -> server relay)
    // ------------------------------------------------------------------

    private fun handleNotify(json: JSONObject) {
        val kind = json.optString("kind", "response")
        val title = json.optString("title", "Hermes")
        val message = json.optString("message", "")
        val host = json.optString("host", "")
        val sessionId = json.optString("session_id", "")

        // Remember which session this came from so the next spoken message
        // can be routed back to it (answering a clarify prompt, etc.).
        if (sessionId.isNotEmpty()) {
            replySessionId = sessionId
            replySessionTitle = title
            runOnUiThread { updateReplyBadge() }
        }

        setStatus("$title\n$message", StatusRingView.State.CONNECTED)
        chatHistory.append(ChatMessage("notify", "$title — $message"))
        renderHistory()

        val urgent = kind == "question" || kind == "approval"
        showSystemNotification(title, message, host, urgent)

        // Speak the alert aloud ONLY when a Bluetooth device is connected
        if (isBluetoothConnected()) {
            tts?.speak("$title. $message", TextToSpeech.QUEUE_FLUSH, null, "notify")
        }
    }

    /** Show a "reply to <session>" hint when a targeted reply is armed. */
    private fun updateReplyBadge() {
        subText.text = if (replySessionId.isNotEmpty()) {
            "Reply goes to: $replySessionTitle — tap to speak"
        } else {
            val n = chatHistory.queue.size
            if (n > 0) {
                "$n message${if (n == 1) "" else "s"} queued — will send when connected"
            } else {
                "Tap to speak · wake word: \"Hey Hermes\""
            }
        }
    }

    private fun showSystemNotification(title: String, message: String, host: String, urgent: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message + if (host.isNotEmpty()) " (from $host)" else "")
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setPriority(if (urgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)

        if (urgent) {
            builder.setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)
        }
        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    // ------------------------------------------------------------------
    // Audio playback (Bluetooth-gated autoplay)
    // ------------------------------------------------------------------

    private var isSpeaking = false

    private fun playAudioStream() {
        audioOutputStream?.close()
        audioOutputStream = null

        audioTempFile?.let { file ->
            // Only auto-play when a Bluetooth audio device is connected.
            if (!isBluetoothConnected()) {
                runOnUiThread {
                    setStatus("Response ready — connect Bluetooth to hear it, or tap to play", StatusRingView.State.CONNECTED)
                }
                return
            }
            runOnUiThread {
                playFile(file)
            }
        }
    }

    private fun playPendingAudio() {
        audioTempFile?.let { playFile(it) }
    }

    private fun playFile(file: File) {
        isSpeaking = true
        setStatus("Speaking...", StatusRingView.State.SPEAKING)
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setOnCompletionListener {
                isSpeaking = false
                mediaPlayer = null
                // Feature 1: after the response finishes playing, automatically listen again
                scheduleAutoListen()
            }
            prepare()
            start()
        }
    }

    private fun scheduleAutoListen() {
        if (autoListenScheduled) return
        autoListenScheduled = true
        statusText.postDelayed({
            autoListenScheduled = false
            // If the user tapped something else in the meantime, don't steal the mic
            if (isSpeaking || voskService != null || flushingQueue) return@postDelayed
            // Offline: return to wake-word mode rather than auto-dictating
            if (isConnected) startListening() else startVoskWakeWord()
        }, 700)
    }

    // ------------------------------------------------------------------
    // Bluetooth detection
    // ------------------------------------------------------------------

    private fun isBluetoothConnected(): Boolean {
        // Check A2DP output devices (no permission needed for output device list)
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        for (device in audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) {
                return true
            }
        }
        // Fallback: adapter state
        return try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            adapter != null && adapter.isEnabled &&
                adapter.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothProfile.STATE_CONNECTED
        } catch (e: SecurityException) {
            false
        }
    }

    // ------------------------------------------------------------------
    // Speech recognition (online path)
    // ------------------------------------------------------------------

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Auto-start listening if invoked while the app is already open in the background
        if (intent.action == Intent.ACTION_ASSIST || intent.action == "com.example.hermesassistant.START_LISTENING") {
            if (isConnected) startListening() else startOfflineDictation()
        }
    }

    private fun startListening() {
        // Stop wake word if it's currently running so we can grab the mic
        voskService?.stop()
        voskService = null

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
        }
        speechRecognizer.startListening(intent)
        setStatus("Listening...", StatusRingView.State.LISTENING)
    }

    private fun setupSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                setStatus("Thinking...", StatusRingView.State.THINKING)
            }
            override fun onError(error: Int) {
                // Google STT may fail when offline — fall back to Vosk dictation
                if (!isConnected) {
                    startOfflineDictation()
                } else {
                    setStatus("Error listening. Try again.", StatusRingView.State.IDLE)
                    startVoskWakeWord()
                }
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val userText = matches[0]
                    chatHistory.append(ChatMessage("user", userText))
                    renderHistory()
                    setStatus("You: $userText", StatusRingView.State.THINKING)

                    // If a targeted reply is armed (a notify arrived with a
                    // session_id), wrap the message so the server routes it
                    // to that specific Hermes session.
                    val payload = if (replySessionId.isNotEmpty()) {
                        "{\"message\": ${JSONObject.quote(userText)}, \"session_id\": ${JSONObject.quote(replySessionId)}}"
                    } else {
                        userText
                    }

                    if (isConnected && webSocket?.send(payload) == true) {
                        // Sent successfully — clear the targeted reply
                        if (replySessionId.isNotEmpty()) {
                            replySessionId = ""
                            replySessionTitle = ""
                            runOnUiThread { updateReplyBadge() }
                        }
                    } else {
                        // Offline (or dropped mid-send): queue it persistently
                        isConnected = false
                        setStatus("Offline — message queued", StatusRingView.State.IDLE)
                        chatHistory.enqueue(userText)
                        renderHistory()
                        updateQueueBadge()
                        connectWebSocket()
                    }
                } else {
                    // Nothing recognized — back to wake word
                    startVoskWakeWord()
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun sendToHermes(text: String) {
        val json = """{"message": "$text"}"""
        val body = json.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

        val request = Request.Builder()
            .url("http://100.123.127.108:8000/chat")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { setStatus("Connection Error: ${e.message}", StatusRingView.State.IDLE) }
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    runOnUiThread { setStatus("Error from server: ${response.code}", StatusRingView.State.IDLE) }
                    return
                }

                val responseText = response.header("X-Response-Text", "Audio received") ?: "Audio received"
                runOnUiThread {
                    setStatus("Hermes: $responseText", StatusRingView.State.CONNECTED)
                    chatHistory.append(ChatMessage("hermes", responseText))
                    renderHistory()
                }

                val tempFile = File(cacheDir, "hermes_reply.mp3")
                val sink = FileOutputStream(tempFile)
                sink.use { it.write(response.body!!.bytes()) }

                runOnUiThread {
                    if (isBluetoothConnected()) {
                        playFile(tempFile)
                    } else {
                        audioTempFile = tempFile
                        setStatus("Response ready — connect Bluetooth to hear it, or tap to play", StatusRingView.State.CONNECTED)
                    }
                }
            }
        })
    }

    // ------------------------------------------------------------------
    // Chat history rendering
    // ------------------------------------------------------------------

    private fun renderHistory() {
        historyList.removeAllViews()
        chatHistory.messages.forEach { addBubble(it) }
        historyScroll.post { historyScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun addBubble(m: ChatMessage) {
        val bubbleMaxWidth = (resources.displayMetrics.widthPixels * 0.8f).toInt()
        val tv = TextView(this).apply {
            text = m.text + if (m.queued) "\n\u23F3 queued (offline)" else ""
            textSize = 14f
            setTextColor(0xFFE5E7EB.toInt())
            setPadding(dp(14), dp(10), dp(14), dp(10))
            maxWidth = bubbleMaxWidth
            isClickable = false
        }

        val bg = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
        }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) }

        when (m.role) {
            "user" -> {
                bg.setColor(if (m.queued) 0xFF3B2F1A.toInt() else 0xFF1D4ED8.toInt())
                lp.gravity = android.view.Gravity.END
            }
            "hermes" -> {
                bg.setColor(0xFF1E293B.toInt())
                lp.gravity = android.view.Gravity.START
            }
            else -> { // notify
                bg.setColor(0xFF111827.toInt())
                bg.setStroke(dp(1), 0xFF334155.toInt())
                lp.gravity = android.view.Gravity.CENTER
                tv.setTextColor(0xFF93C5FD.toInt())
                tv.textSize = 13f
            }
        }
        tv.background = bg
        historyList.addView(tv, lp)
    }

    private fun updateQueueBadge() {
        val n = chatHistory.queue.size
        subText.text = if (n > 0) {
            "$n message${if (n == 1) "" else "s"} queued — will send when connected"
        } else {
            "Tap to speak · wake word: \"Hey Hermes\""
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun setStatus(text: String, state: StatusRingView.State) {
        statusText.text = text
        statusRing.state = state
    }

    override fun onDestroy() {
        super.onDestroy()
        stopReconnectLoop()
        speechRecognizer.destroy()
        tts?.stop()
        tts?.shutdown()
        mediaPlayer?.release()
        webSocket?.cancel()
    }
}
