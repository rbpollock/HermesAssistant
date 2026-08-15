package com.example.hermesassistant

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import androidx.core.app.RemoteInput
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
    private lateinit var typeToggleButton: android.widget.ImageButton
    private lateinit var textInputRow: LinearLayout
    private lateinit var textInput: android.widget.EditText
    private lateinit var sendButton: android.widget.ImageButton
    private lateinit var sessionChipsRow: LinearLayout
    private lateinit var sessionChipsScroll: android.widget.HorizontalScrollView
    private lateinit var settingsButton: android.widget.ImageButton
    private lateinit var notificationManager: NotificationManager
    private lateinit var sessionStore: SessionStore
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

    // The session the current exchange belongs to — used to tag the
    // hermes reply bubble with the same session as the user message.
    private var activeSessionId: String = ""
    private var activeSessionTitle: String = ""

    companion object {
        private const val NOTIFICATION_CHANNEL = "hermes_events"
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
        typeToggleButton = findViewById(R.id.typeToggleButton)
        textInputRow = findViewById(R.id.textInputRow)
        textInput = findViewById(R.id.textInput)
        sendButton = findViewById(R.id.sendButton)
        sessionChipsRow = findViewById(R.id.sessionChipsRow)
        sessionChipsScroll = findViewById(R.id.sessionChipsScroll)
        settingsButton = findViewById(R.id.settingsButton)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        sessionStore = SessionStore(this)
        chatHistory = ChatHistoryStore(this)

        createNotificationChannel()
        startHermesForegroundService()
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
        renderSessionChips()

        speakButton.setOnClickListener {
            if (dictationMode) {
                // Tap again while dictating = cancel
                exitDictationMode()
            } else if (!isConnected) {
                // Offline: transcribe locally with Vosk and queue the message
                startOfflineDictation()
            } else if (mediaPlayer == null && audioTempFile != null && !isSpeaking && playbackQueue.isEmpty()) {
                // A response is waiting (no Bluetooth autoplay happened) — tap plays it
                playPendingAudio()
            } else {
                startListening()
            }
        }

        // Circular keyboard icon: ~12% of screen width, top-right of the
        // bottom section. The soft keyboard ONLY shows when this icon is
        // pressed — never on assistant activation.
        val iconSize = (resources.displayMetrics.widthPixels * 0.12f).toInt()
        typeToggleButton.layoutParams = typeToggleButton.layoutParams.apply { width = iconSize; height = iconSize }
        typeToggleButton.setOnClickListener {
            val showing = textInputRow.visibility == View.VISIBLE
            textInputRow.visibility = if (showing) View.GONE else View.VISIBLE
            if (!showing) {
                textInput.requestFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.showSoftInput(textInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            } else {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.hideSoftInputFromWindow(textInput.windowToken, 0)
            }
        }

        // Send typed text (both the send icon and the IME action)
        val sendAction = {
            val text = textInput.text?.toString().orEmpty()
            if (text.isNotBlank()) {
                textInput.text?.clear()
                sendUserMessage(text)
            }
        }
        sendButton.setOnClickListener { sendAction() }
        textInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                sendAction()
                true
            } else {
                false
            }
        }

        // Gear icon (top-left): open settings
        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Auto-start listening if invoked via the OS Assistant hardware button,
        // the headphone/BT button (VOICE_COMMAND), or our custom action from
        // the VoiceInteractionSession.
        if (isVoiceInvocation(intent?.action)) {
            if (isConnected) startListening() else startOfflineDictation()
        }

        // Notification tap: select the session chip for that notification
        handleTargetSessionIntent(intent)
    }

    /** True when the intent asks us to act as the voice assistant. */
    private fun isVoiceInvocation(action: String?): Boolean {
        return action == Intent.ACTION_ASSIST
            || action == Intent.ACTION_VOICE_COMMAND
            || action == "com.example.hermesassistant.START_LISTENING"
    }

    /**
     * If launched by tapping a notification, arm the reply target for the
     * session that produced it and highlight its chip.
     */
    private fun handleTargetSessionIntent(intent: Intent?) {
        val sessionId = intent?.getStringExtra("target_session_id").orEmpty()
        if (sessionId.isEmpty()) return

        val title = intent?.getStringExtra("target_session_title").orEmpty()

        // Make sure it's known to the chip list (e.g. after app restart)
        sessionStore.upsert(
            KnownSession(
                id = sessionId,
                title = sessionTitleFromNotify(title, sessionId),
            )
        )
        replySessionId = sessionId
        replySessionTitle = title
        updateReplyBadge()
        renderSessionChips()

        // Also surface the message context in the history if it was a notify
        val msg = intent?.getStringExtra("target_message").orEmpty()
        if (msg.isNotEmpty()) {
            chatHistory.append(ChatMessage("notify", "$title — $msg"))
            renderHistory()
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
        // Register the progress listener immediately (valid before init
        // completes; re-registered in the callback too). onDone drives the
        // unified playback queue, so without this a TTS alert could stall
        // every later audio item forever.
        registerTtsListener()
    }

    private fun registerTtsListener() {
        tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                runOnUiThread { onPlaybackItemDone() }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                runOnUiThread { onPlaybackItemDone() }
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
                runOnUiThread { onPlaybackItemDone() }
            }
        })
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_POST_NOTIFICATIONS)
        }
    }

    /** Start the foreground service that keeps the app alive in the background. */
    private fun startHermesForegroundService() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(Intent(this, HermesForegroundService::class.java))
            } else {
                startService(Intent(this, HermesForegroundService::class.java))
            }
        } catch (e: Exception) {
            // e.g. BackgroundServiceStartNotAllowedException — app not
            // in a state to start it right now; will start next launch.
        }
    }

    /** Open this app's notification settings page (where the toggle lives). */
    private fun openNotificationSettings() {
        try {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            }
            startActivity(intent)
        } catch (e: Exception) {
            // Fallback: app details page
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                })
            } catch (e2: Exception) {
                setStatus("Enable notifications in system settings", StatusRingView.State.IDLE)
            }
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
            .url("${ServerConfig.wsBase(this)}/chat/stream")
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
                                chatHistory.append(
                                    ChatMessage(
                                        "hermes",
                                        json.optString("message"),
                                        sessionId = activeSessionId,
                                        sessionTitle = activeSessionTitle,
                                    )
                                )
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
                        // Any failure in the message path lands here. Show the
                        // REAL exception (not just the raw frame) so the
                        // status line tells us what actually broke. Note: a
                        // valid-JSON notify that fails inside handleNotify()
                        // ALSO lands here — that's why we log e.message.
                        val diag = "⚠ WS msg error: ${e.javaClass.simpleName}: ${e.message?.take(120)}"
                        chatHistory.append(ChatMessage("notify", diag))
                        renderHistory()
                        setStatus(diag, StatusRingView.State.CONNECTED)
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

            // Track it in the session chip list
            sessionStore.upsert(
                KnownSession(
                    id = sessionId,
                    title = sessionTitleFromNotify(title, sessionId),
                    host = host,
                )
            )
            runOnUiThread { renderSessionChips() }
        }

        setStatus("$title\n$message", StatusRingView.State.CONNECTED)
        chatHistory.append(
            ChatMessage(
                "notify",
                "$title — $message",
                sessionId = sessionId,
                sessionTitle = if (sessionId.isNotEmpty()) sessionTitleFromNotify(title, sessionId) else "",
            )
        )
        renderHistory()

        val urgent = kind == "question" || kind == "approval"
        try {
            showSystemNotification(title, message, host, urgent, sessionId)
        } catch (e: Exception) {
            // A notification failure must not kill the message pipeline;
            // log it so it's visible instead of being misreported as a
            // parse error by the WS handler's catch-all.
            chatHistory.append(ChatMessage("notify", "⚠ notify error: ${e.javaClass.simpleName}: ${e.message?.take(120)}"))
            renderHistory()
        }

        // Speak the alert aloud ONLY when a Bluetooth device is connected,
        // and route it through the playback queue so it never talks over
        // response audio that's already playing.
        if (isBluetoothConnected()) {
            enqueuePlayback(PlaybackItem(audioFile = null, spokenText = "$title. $message"))
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

    // ------------------------------------------------------------------
    // Session chips — pick which session to talk to
    // ------------------------------------------------------------------

    /** Parse a friendly title out of a notify title (e.g. "Hermes finished · <title>"). */
    private fun sessionTitleFromNotify(title: String, sessionId: String): String {
        val idx = title.indexOf("·")
        if (idx >= 0 && idx + 1 < title.length) {
            val after = title.substring(idx + 1).trim()
            if (after.isNotEmpty()) return after
        }
        // Fall back to a short id so the chip still means something
        return if (sessionId.length > 12) "…${sessionId.takeLast(10)}" else sessionId
    }

    /** Render the horizontal session chips; the selected one is highlighted. */
    private fun renderSessionChips() {
        sessionChipsRow.removeAllViews()
        if (sessionStore.sessions.isEmpty()) return

        sessionStore.sessions.forEach { session ->
            val isSelected = session.id == replySessionId
            val chip = TextView(this).apply {
                text = session.title
                textSize = 12f
                setTextColor(if (isSelected) 0xFF0B1220.toInt() else 0xFFE5E7EB.toInt())
                setPadding(dp(14), dp(8), dp(14), dp(8))
                isClickable = true
                isFocusable = true
                background = chipBackground(isSelected)
                setOnClickListener {
                    if (replySessionId == session.id) {
                        // Tap again to untarget (back to default daily session)
                        replySessionId = ""
                        replySessionTitle = ""
                    } else {
                        replySessionId = session.id
                        replySessionTitle = session.title
                    }
                    updateReplyBadge()
                    renderSessionChips()
                }
            }
            sessionChipsRow.addView(
                chip,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
            )
        }

        // Scroll to the end so the newest chip is visible in the band
        sessionChipsScroll.post { sessionChipsScroll.fullScroll(View.FOCUS_RIGHT) }
    }

    private fun chipBackground(selected: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(16).toFloat()
        if (selected) {
            setColor(0xFF60A5FA.toInt())
        } else {
            setColor(0xFF1E293B.toInt())
            setStroke(dp(1), 0xFF334155.toInt())
        }
    }

    private fun showSystemNotification(title: String, message: String, host: String, urgent: Boolean, sessionId: String = "") {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            // Don't fail silently — tell the user where to fix it, re-request
            // (works if they previously dismissed the dialog), and make the
            // status line tappable to open notification settings.
            setStatus("Notifications blocked — tap here to enable", StatusRingView.State.CONNECTED)
            statusText.isClickable = true
            statusText.setOnClickListener { openNotificationSettings() }
            requestNotificationPermissionIfNeeded()
            return
        }
        statusText.setOnClickListener(null)
        statusText.isClickable = false

        // sessionId.hashCode() can be negative; Samsung silently drops
        // notifications with negative IDs. Mask the sign bit so IDs are
        // always in the non-negative range, and shift into 100+ so they
        // never collide with the foreground service (ID 1) or the reply
        // notifications (200+).
        val notifId = (sessionId.hashCode() and 0x7fffffff) % 1000000 + 100

        // Tapping the notification opens the app and selects the session
        // chip for the session that produced this notification.
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("target_session_id", sessionId)
            putExtra("target_session_title", title)
            putExtra("target_message", message)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            notifId + 100,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // "Reply" action: type directly in the notification shade and send
        // to that session (works even if the app process is not running).
        val replyIntent = Intent(this, NotificationReplyReceiver::class.java).apply {
            putExtra(NotificationReplyReceiver.EXTRA_SESSION_ID, sessionId)
            putExtra(NotificationReplyReceiver.EXTRA_SESSION_TITLE, title)
            putExtra(NotificationReplyReceiver.EXTRA_NOTIFY_TITLE, title)
        }
        val replyPendingIntent = PendingIntent.getBroadcast(
            this,
            notifId + 200,
            replyIntent,
            // FLAG_MUTABLE is REQUIRED for actions with RemoteInput on
            // Android 12+: the system must be able to inject the typed
            // reply text into the PendingIntent. With FLAG_IMMUTABLE,
            // NotificationManager.notify() throws IllegalArgumentException
            // ("PendingIntents attached to actions with remote inputs must
            // be mutable") and the notification is never posted. The intent
            // is explicit to our non-exported receiver, so mutability is
            // safe here.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        val remoteInput = RemoteInput.Builder(NotificationReplyReceiver.KEY_TEXT_REPLY)
            .setLabel("Reply to Hermes")
            .build()
        val replyAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "Reply",
            replyPendingIntent
        ).addRemoteInput(remoteInput).build()

        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message + if (host.isNotEmpty()) " (from $host)" else "")
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(replyAction)
            .setPriority(if (urgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)

        if (urgent) {
            builder.setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)
        }
        // CRITICAL: use the sanitized notifId (100+ range). The raw
        // sessionId.hashCode() is negative for many ids and Samsung
        // drops — and on some builds throws on — negative notification
        // IDs. This was the cause of both missing notifications and
        // the "WS msg error" status.
        notificationManager.notify(notifId, builder.build())
    }

    // ------------------------------------------------------------------
    // Audio playback — unified FIFO queue (Bluetooth-gated autoplay)
    //
    // Both audio sources (server response MP3 via MediaPlayer, and notify
    // alerts via TextToSpeech) feed the same queue. Items play strictly
    // one at a time: the next starts only after the current one finishes,
    // so a "Hermes finished" alert arriving mid-response is queued, never
    // spoken over the top of the audio already playing.
    // ------------------------------------------------------------------

    private data class PlaybackItem(val audioFile: File?, val spokenText: String?)

    private val playbackQueue = ArrayDeque<PlaybackItem>()
    private var isSpeaking = false
    // Guards onPlaybackItemDone against double-firing (some TTS engines
    // fire both onError and onDone for one utterance; a stale callback
    // must not skip the next queued item).
    private var playbackItemInFlight = false

    // Watchdog: if an item doesn't finish within this long (TTS onDone
    // not fired, MediaPlayer error, etc.), force-advance so the queue can
    // never jam permanently with isSpeaking=true.
    private val playbackWatchdog = android.os.Handler(android.os.Looper.getMainLooper())
    private var playbackWatchdogRunnable: Runnable? = null
    private val PLAYBACK_WATCHDOG_MS = 20000L

    private fun armPlaybackWatchdog() {
        playbackWatchdogRunnable?.let { playbackWatchdog.removeCallbacks(it) }
        val r = Runnable {
            // Timed out — assume the item is stuck and move on
            runOnUiThread { onPlaybackItemDone() }
        }
        playbackWatchdogRunnable = r
        playbackWatchdog.postDelayed(r, PLAYBACK_WATCHDOG_MS)
    }

    private fun disarmPlaybackWatchdog() {
        playbackWatchdogRunnable?.let { playbackWatchdog.removeCallbacks(it) }
        playbackWatchdogRunnable = null
    }

    private fun enqueuePlayback(item: PlaybackItem) {
        playbackQueue.addLast(item)
        if (!isSpeaking) startNextPlayback()
    }

    private fun startNextPlayback() {
        val item = playbackQueue.removeFirstOrNull()
        if (item == null) {
            isSpeaking = false
            disarmPlaybackWatchdog()
            // Feature 1: after the response finishes playing, automatically listen again
            scheduleAutoListen()
            return
        }
        isSpeaking = true
        setStatus("Speaking...", StatusRingView.State.SPEAKING)
        playbackItemInFlight = true

        val file = item.audioFile
        val text = item.spokenText
        when {
            file != null -> {
                // Server-generated response audio. MediaPlayer has reliable
                // onCompletion/onError callbacks, so no watchdog needed here
                // (a long response may legitimately play for 30-60s).
                try {
                    mediaPlayer?.release()
                    mediaPlayer = MediaPlayer().apply {
                        setDataSource(file.absolutePath)
                        setOnCompletionListener { runOnUiThread { onPlaybackItemDone() } }
                        setOnErrorListener { _, _, _ -> runOnUiThread { onPlaybackItemDone() }; true }
                        prepare()
                        start()
                    }
                } catch (e: Exception) {
                    // Bad/empty file — skip it, keep the queue moving
                    runOnUiThread { onPlaybackItemDone() }
                }
            }
            text != null -> {
                // Notify alert via TTS; onDone() fires the next item. If TTS
                // isn't ready or speak fails, don't block the queue. The
                // watchdog guards against onDone never firing (a known
                // Samsung TTS quirk) which would otherwise jam the queue.
                if (tts == null) {
                    onPlaybackItemDone()
                } else {
                    armPlaybackWatchdog()
                    val result = try {
                        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "notify") ?: TextToSpeech.ERROR
                    } catch (e: Exception) {
                        TextToSpeech.ERROR
                    }
                    if (result == TextToSpeech.ERROR) {
                        onPlaybackItemDone()
                    }
                }
            }
            else -> onPlaybackItemDone()
        }
    }

    private fun onPlaybackItemDone() {
        if (!playbackItemInFlight) return // stale callback — ignore
        playbackItemInFlight = false
        disarmPlaybackWatchdog()
        mediaPlayer?.release()
        mediaPlayer = null
        startNextPlayback()
    }

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
                enqueuePlayback(PlaybackItem(audioFile = file, spokenText = null))
            }
        }
    }

    private fun playPendingAudio() {
        audioTempFile?.let {
            enqueuePlayback(PlaybackItem(audioFile = it, spokenText = null))
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
        setIntent(intent) // keep getIntent() in sync for singleTask relaunch
        // Auto-start listening if invoked while the app is already open in the background
        if (isVoiceInvocation(intent.action)) {
            if (isConnected) startListening() else startOfflineDictation()
        }
        // Notification tap: select the session chip for that notification
        handleTargetSessionIntent(intent)
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
                    sendUserMessage(matches[0])
                } else {
                    // Nothing recognized — back to wake word
                    startVoskWakeWord()
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    /**
     * Shared send path for both voice and typed text.
     * Routes to the targeted reply session when armed, otherwise the
     * daily android session; queues persistently when offline.
     */
    private fun sendUserMessage(rawText: String) {
        val userText = rawText.trim()
        if (userText.isEmpty()) return

        // The message belongs to whichever session is currently targeted
        // (replySessionId may be cleared after a successful send, so
        // capture it first). Used to tag both the user bubble and the
        // hermes reply bubble with the same session.
        activeSessionId = replySessionId
        activeSessionTitle = replySessionTitle

        chatHistory.append(
            ChatMessage("user", userText, sessionId = activeSessionId, sessionTitle = activeSessionTitle)
        )
        renderHistory()
        setStatus("You: $userText", StatusRingView.State.THINKING)

        // If a targeted reply is armed (a notify arrived with a
        // session_id), wrap the message so the server routes it to that
        // specific Hermes session.
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
                runOnUiThread {
                    updateReplyBadge()
                    renderSessionChips()
                }
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
    }

    private fun sendToHermes(text: String) {
        val json = """{"message": "$text"}"""
        val body = json.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

        val request = Request.Builder()
            .url("${ServerConfig.httpBase(this)}/chat")
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
                    chatHistory.append(
                        ChatMessage(
                            "hermes",
                            responseText,
                            sessionId = activeSessionId,
                            sessionTitle = activeSessionTitle,
                        )
                    )
                    renderHistory()
                }

                val tempFile = File(cacheDir, "hermes_reply.mp3")
                val sink = FileOutputStream(tempFile)
                sink.use { it.write(response.body!!.bytes()) }

                runOnUiThread {
                    if (isBluetoothConnected()) {
                        enqueuePlayback(PlaybackItem(audioFile = tempFile, spokenText = null))
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
            isClickable = true
            isFocusable = true
            setOnClickListener {
                selectSessionFromMessage(m)
            }
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

    /**
     * Tapping a message targets the session it belongs to: the matching
     * chip gets selected and the reply badge arms. Messages with no
     * session (the phone's own daily chat) clear the target back to
     * default.
     */
    private fun selectSessionFromMessage(m: ChatMessage) {
        val targetId = m.sessionId
        if (targetId.isNotEmpty()) {
            val targetTitle = m.sessionTitle.ifEmpty { "…${targetId.takeLast(10)}" }
            // Make sure the chip exists (may have arrived via a different path)
            sessionStore.upsert(KnownSession(id = targetId, title = targetTitle))
            replySessionId = targetId
            replySessionTitle = targetTitle
            setStatus("Replying to: $targetTitle", StatusRingView.State.CONNECTED)
        } else {
            replySessionId = ""
            replySessionTitle = ""
            setStatus("Reply target cleared — daily phone chat", StatusRingView.State.CONNECTED)
        }
        updateReplyBadge()
        renderSessionChips()
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
        // Status line is single-line (maxLines=1 + ellipsize). Collapse
        // newlines so multi-line payloads (notify title+message, long
        // Hermes replies) render as one clean line instead of pushing the
        // speak button off the half-screen panel. Full text is in history.
        val oneLine = text.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        statusText.text = oneLine
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
