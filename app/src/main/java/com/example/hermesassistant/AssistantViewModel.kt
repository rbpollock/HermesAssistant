package com.example.hermesassistant

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.ByteString
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Everything the UI needs to render, in one immutable snapshot. */
data class AssistantUiState(
    val status: String = "Ready",
    val statusState: StatusRingView.State = StatusRingView.State.IDLE,
    val isConnected: Boolean = false,
    val messages: List<ChatMessage> = emptyList(),
    val sessions: List<KnownSession> = emptyList(),
    val replySessionId: String = "",
    val replySessionTitle: String = "",
    val queueCount: Int = 0,
    val voiceActive: Boolean = false,
    val speakButtonLabel: String = "TAP TO SPEAK",
    val subTextLabel: String = "Tap to speak · wake word: \"Hey Hermes\"",
    // F6: true when a response is parked (BT-only + no headset) and
    // waiting for a tap to play — the speak button shows "TAP TO PLAY".
    val hasParkedAudio: Boolean = false,
    // Diagnostics: the most recent notify event received over the WS,
    // with a timestamp, so the settings screen can show whether the
    // relay → app link is alive (empty = nothing ever received).
    val lastNotifyKind: String = "",
    val lastNotifyTitle: String = "",
    val lastNotifyAt: Long = 0L,
    // Live mic amplitude (0f..1f, smoothed) while listening — drives the
    // orb/waveform. 0 when not listening.
    val rmsLevel: Float = 0f,
)

/**
 * Phase-2 state layer: owns the network (RelayClient), audio (AudioPlayer),
 * speech (VoiceInput) and persistence (ChatHistoryStore / SessionStore)
 * delegates, and exposes every UI-relevant value as one [StateFlow].
 *
 * The UI (legacy Views today, Compose in Phase 3+) renders this state and
 * forwards user actions back through the public methods. Business logic
 * never touches Views directly.
 */
class AssistantViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(AssistantUiState())
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

    private val context: Context get() = getApplication()

    // Delegates
    private val relay = RelayClient(context)
    private val audio = AudioPlayer(context)
    private val voice = VoiceInput(context)
    private val chatHistory = ChatHistoryStore(context)
    private val sessionStore = SessionStore(context)
    private val notificationManager = androidx.core.app.NotificationManagerCompat.from(context)

    // WS state
    private var pendingMessage: String? = null

    // Audio stream accumulation (buffered fallback for the pipe path)
    private var audioTempFile: File? = null
    private var audioOutputStream: FileOutputStream? = null

    // Targeted reply state
    private var replySessionId: String = ""
    private var replySessionTitle: String = ""

    // The session a message belongs to (tagged on bubbles)
    private var activeSessionId: String = ""
    private var activeSessionTitle: String = ""

    // Offline queue flushing
    private var flushingQueue = false
    private var autoListenScheduled = false
    private var lastTurnUserInitiated = false
    // F5b: set after startWakeWord() so the "queued" confirmation is not
    // immediately overwritten by the wake-word status line.
    private var queuedConfirmation: String? = null

    // Notify dedupe window
    private val recentNotifyFingerprints = ArrayDeque<Pair<Long, String>>()

    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        createNotificationChannel()
        // Boot the wake-word foreground service from the shared state
        // layer. This was previously done by MainActivity.onCreate only —
        // the Phase 3 Compose surface (the new wake-word target) never
        // started it, so "Hey Hermes" silently died after an update /
        // reboot when the service wasn't already running. Starting here
        // covers ANY entry surface (Compose sheet, legacy activity,
        // notification tap) with one call.
        startForegroundServiceIfPossible()
        wireRelay()
        wireAudio()
        wireVoice()
        audio.initTts()
    }

    private fun startForegroundServiceIfPossible() {
        try {
            val i = android.content.Intent(context, HermesForegroundService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        } catch (e: Exception) {
            // BackgroundServiceStartNotAllowedException or similar — the
            // service will be started next launch (activities also call
            // notifyMicPermissionGranted(), which starts it too).
        }
    }

    // ------------------------------------------------------------------
    // Delegate wiring
    // ------------------------------------------------------------------

    private fun wireRelay() {
        relay.attach(object : RelayClient.Listener {
            override fun onConnected() {
                _uiState.update { it.copy(isConnected = true) }
               setStatusInternal("Connected to server", StatusRingView.State.CONNECTED)
                pendingMessage?.let { msg ->
                    if (relay.send(msg)) pendingMessage = null
                }
                flushQueueIfAny()
            }

            override fun onDisconnected(reason: String) {
                _uiState.update { it.copy(isConnected = false) }
               setStatusInternal(reason, StatusRingView.State.IDLE)
            }

            override fun onTextResponse(message: String) {
                handleAssistantReply(message)
            }

            override fun onStatus(message: String) {
               setStatusInternal(message, StatusRingView.State.THINKING)
            }

            override fun onNotify(json: JSONObject) {
                handleNotify(json)
            }

            override fun onAudioBytes(bytes: ByteString) {
                // Progressive playback: first chunk starts the stream so
                // audio begins as soon as data flows, not at audio_end.
                // Bytes are also buffered to a temp file for fallback.
                if (audioOutputStream == null) {
                    audioTempFile = File(context.cacheDir, "stream_${System.currentTimeMillis()}.mp3")
                    audioOutputStream = FileOutputStream(audioTempFile)
                    audio.streamStart()
                }
                try {
                    audioOutputStream?.write(bytes.toByteArray())
                } catch (e: IOException) {
                    // ignore
                }
                audio.streamWrite(bytes.toByteArray())
            }

            override fun onAudioEnd() {
                try { audioOutputStream?.close() } catch (e: Exception) {}
                audioOutputStream = null
                audio.streamEnd()
                if (audio.streamFailed()) {
                    // Pipe path failed — fall back to the buffered file.
                    audioTempFile?.let { audio.playAudio(it) }
                } else {
                   setStatusInternal("Speaking...", StatusRingView.State.SPEAKING)
                }
                if (flushingQueue) sendNextQueued()
            }
        })
    }

    private fun wireAudio() {
        audio.attach(object : AudioPlayer.Listener {
            override fun onQueueEmpty() {
                scheduleAutoListen()
            }

            override fun onSpeakingStarted() {
               setStatusInternal("Speaking...", StatusRingView.State.SPEAKING)
            }

            override fun onSpeakingStopped() {}

            override fun onStatus(message: String) {
               setStatusInternal(message, StatusRingView.State.CONNECTED)
            }
        })
    }

    private fun wireVoice() {
        voice.attach(object : VoiceInput.Listener {
            override fun onTextCaptured(text: String) {
                sendUserMessage(text)
            }

            override fun onStateChanged(state: VoiceInput.State) {
                when (state) {
                    VoiceInput.State.STT,
                    VoiceInput.State.DICTATION -> {
                        updateSpeakLabel("TAP TO CANCEL")
                        // F1: the mic is OPEN — the orb must show the
                        // listening state (green + real amplitude), not
                        // the idle grey. Previously nothing set
                        // State.LISTENING, so the audio-reactive orb was
                        // dead code.
                        setStatusInternal("Listening...", StatusRingView.State.LISTENING)
                    }
                    VoiceInput.State.IDLE -> {
                        updateSpeakLabel("TAP TO SPEAK")
                        setStatusInternal("Listening for \"Hey Hermes\"", StatusRingView.State.IDLE)
                    }
                }
                // Keep voiceActive in sync so the UI can expand the panel
                // when listening begins (and re-arm wake word when idle).
                _uiState.update { it.copy(voiceActive = voice.isActive, rmsLevel = 0f) }
            }

            override fun onError(message: String) {
                ChimePlayer.playStop(context)
                if (_uiState.value.isConnected) {
                    // F5a: arm the wake word FIRST, then show the error —
                    // previously startWakeWord() overwrote the error
                    // status before the user could read it.
                    startWakeWord()
                    setStatusInternal("$message. Try again.", StatusRingView.State.IDLE)
                } else {
                    // Offline: fall back to Vosk dictation
                    startOfflineDictation()
                }
            }

            override fun onListening() {
                ChimePlayer.playStart(context)
            }

            override fun onStoppedListening() {
                ChimePlayer.playStop(context)
            }

            override fun onThinking() {
                setStatusInternal("Thinking...", StatusRingView.State.THINKING)
                _uiState.update { it.copy(rmsLevel = 0f) }
            }

            override fun onRmsLevel(rmsdB: Float) {
                // Google STT reports dB (~-10 quiet .. -60 loud); map to
                // 0..1 and smooth to avoid jitter in the orb.
                val raw = ((rmsdB + 60f) / 50f).coerceIn(0f, 1f)
                _uiState.update {
                    it.copy(rmsLevel = (it.rmsLevel * 0.6f) + (raw * 0.4f))
                }
            }
        })
    }

    // ------------------------------------------------------------------
    // Public actions (called by the UI)
    // ------------------------------------------------------------------

    /** Free the mic and start Google STT (or Vosk when offline). */
    fun beginListening() {
        HermesForegroundService.stopWakeWord()
        if (_uiState.value.isConnected) voice.startListening() else voice.startOfflineDictation()
    }

    fun startOfflineDictation() {
        voice.startOfflineDictation()
    }

    /** The service heard "Hey Hermes" — take over from the wake word. */
    fun onWakeWordHeard() {
        beginListening()
    }

    /** Back to wake-word mode (used after errors / cancelled listens). */
    private fun startWakeWord() {
        HermesForegroundService.startWakeWordNow()
        updateSpeakLabel("TAP TO SPEAK")
        setStatusInternal("Listening for \"Hey Hermes\"", StatusRingView.State.IDLE)
    }

    /** A full phrase was captured by Vosk while offline — queue it. */
    fun handleDictatedText(hypothesis: String?, alreadySent: Boolean = false) {
        val text = (hypothesis ?: "").trim()
        voice.onDictationResult(text)

        if (text.isNotEmpty()) {
            val clean = text
                .replaceFirst("(?i)^(hey )?hermes[\\s,.-]*".toRegex(), "")
                .trim()
            if (clean.isNotEmpty() && !alreadySent) {
                chatHistory.enqueue(clean)
                // F5b: enqueue confirmation must survive — set it AFTER
                // startWakeWord() below so it isn't clobbered.
                queuedConfirmation = clean
            } else {
                chatHistory.append(ChatMessage("user", clean))
            }
            pushState()
        }
        connectIfNeeded()
        startWakeWord()
        queuedConfirmation?.let { clean ->
            queuedConfirmation = null
            // F7: offline condition — IDLE, not CONNECTED.
            setStatusInternal("Offline — queued: $clean", StatusRingView.State.IDLE)
        }
    }

    /** A wake-word HTTP reply landed (service appended history + spoke). */
    fun onReplyReady(replyText: String) {
        if (replyText.isNotEmpty()) {
            audio.rememberSpokenResponse(replyText)
           setStatusInternal("Hermes: $replyText", StatusRingView.State.CONNECTED)
        }
        chatHistory.reload()
        pushState()
    }

    /** Shared primary action for both the big panel button and the
     *  collapsed strip button: cancel, play a parked response, or listen. */
    fun onSpeakButtonPressed() {
        when {
            voice.isActive -> voice.cancel()
            audio.playbackInFlight() -> Unit // ignore taps mid-playback
            audio.pendingAudio() != null -> {
                val f = audio.pendingAudio()
                audio.clearPendingAudio()
                audio.playPending(f)
            }
            !_uiState.value.isConnected -> startOfflineDictation()
            else -> beginListening()
        }
    }

    /** Shared send path for both voice and typed text. */
    fun sendUserMessage(rawText: String) {
        val userText = rawText.trim()
        if (userText.isEmpty()) return

        lastTurnUserInitiated = true

        activeSessionId = replySessionId
        activeSessionTitle = replySessionTitle

        // F15: capture the bubble's timestamp so the offline branch can
        // mark THIS bubble queued (not append a duplicate).
        val userMsg = ChatMessage("user", userText, sessionId = activeSessionId, sessionTitle = activeSessionTitle)
        chatHistory.append(userMsg)
       setStatusInternal("You: $userText", StatusRingView.State.THINKING)
        pushState()

        val payload = if (replySessionId.isNotEmpty()) {
            "{\"message\": ${JSONObject.quote(userText)}, \"session_id\": ${JSONObject.quote(replySessionId)}}"
        } else {
            userText
        }

        if (relay.send(payload)) {
            if (replySessionId.isNotEmpty()) {
                replySessionId = ""
                replySessionTitle = ""
                pushState()
            }
        } else {
            _uiState.update { it.copy(isConnected = false) }
           setStatusInternal("Offline — message queued", StatusRingView.State.IDLE)
            // F15: mark the existing bubble queued — no duplicate append.
            chatHistory.enqueueExisting(userMsg.ts)
            pushState()
            connectIfNeeded()
        }
    }

    /** Select a session chip ("" clears the target back to daily chat). */
    fun selectSession(sessionId: String, sessionTitle: String) {
        replySessionId = sessionId
        replySessionTitle = sessionTitle
        if (sessionId.isNotEmpty()) {
            sessionStore.upsert(KnownSession(id = sessionId, title = sessionTitle.ifEmpty { "…${sessionId.takeLast(10)}" }))
           setStatusInternal("Replying to: $sessionTitle", StatusRingView.State.CONNECTED)
        } else {
           setStatusInternal("Reply target cleared — daily phone chat", StatusRingView.State.CONNECTED)
        }
        pushState()
    }

    /** Tapping a history message targets the session it belongs to. */
    fun selectSessionFromMessage(m: ChatMessage) {
        selectSession(m.sessionId, m.sessionTitle)
    }

    /** Notification tap: pre-select the session that notification came from. */
    fun handleTargetSessionIntent(sessionId: String, title: String, message: String) {
        if (sessionId.isEmpty()) return
        // F4: strip the raw notification title ("Hermes finished · X")
        // so chips, header and sub-line all show the session title "X"
        // consistently with every other path.
        val cleanTitle = sessionTitleFromNotify(title, sessionId)
        sessionStore.upsert(KnownSession(id = sessionId, title = cleanTitle))
        replySessionId = sessionId
        replySessionTitle = cleanTitle
        if (message.isNotEmpty()) {
            chatHistory.append(ChatMessage("notify", "$cleanTitle — $message"))
        }
        pushState()
    }

    /** Open the network connection (idempotent). */
    fun connectIfNeeded() {
        if (!relay.isConnected) relay.connect()
    }

    /** Re-read history from disk (inline shade replies appended to it). */
    fun reloadHistory() {
        chatHistory.reload()
        pushState()
    }

    /**
     * Reconnect against the current ServerConfig. Called after the user
     * changes host/port in settings — the old socket is cancelled so the
     * next connect targets the new server (journey 7: no stale "connecting"
     * state against the old host).
     */
    fun reconfigureServer() {
        relay.cancel()
        connectIfNeeded()
    }

    /** Expose status updates to UI-driven flows (e.g. update install). */
    fun setStatus(text: String, state: StatusRingView.State) {
        setStatusInternal(text, state)
    }

    // ------------------------------------------------------------------
    // Notify events from ANY Hermes session
    // ------------------------------------------------------------------

    private fun handleNotify(json: JSONObject) {
        val kind = json.optString("kind", "response")
        val title = json.optString("title", "Hermes")
        val message = json.optString("message", "")
        val host = json.optString("host", "")
        val sessionId = json.optString("session_id", "")

        // Diagnostics: remember the most recent WS notify event so the
        // settings screen can show the relay → app link is alive.
        _uiState.update {
            it.copy(
                lastNotifyKind = kind,
                lastNotifyTitle = title,
                lastNotifyAt = System.currentTimeMillis(),
            )
        }

        val isDuplicate = isDuplicateNotify(kind, title, message, sessionId)

        if (json.optString("event") == "injected") {
            chatHistory.markInjected(sessionId)
            pushState()
            return
        }

        // Q3 decision: auto-arm the reply target ONLY when Hermes is
        // asking something (question/approval). A routine "Hermes
        // finished" notify must NOT silently redirect the next voice or
        // typed message to that session — that surprised users. Tapping
        // the notification body still arms deliberately via
        // handleTargetSessionIntent.
        val isAsking = kind == "question" || kind == "approval"
        if (isAsking && sessionId.isNotEmpty()) {
            replySessionId = sessionId
            replySessionTitle = sessionTitleFromNotify(title, sessionId)
        }

        chatHistory.append(
            ChatMessage(
                "notify",
                message,
                sessionId = sessionId,
                sessionTitle = if (sessionId.isNotEmpty()) sessionTitleFromNotify(title, sessionId) else "",
            )
        )

        // A4: an incoming notify is NOT a user-initiated turn.
        val isReplyToOwnTurn = json.optBoolean("already_spoken", false) ||
            audio.isResponseEcho(message) ||
            json.optString("event") == "injected"
        if (!isReplyToOwnTurn) {
            lastTurnUserInitiated = false
        }
        pushState()

        val urgent = kind == "question" || kind == "approval"
        if (!isDuplicate) {
            try {
                showSystemNotification(title, message, host, urgent, sessionId)
            } catch (e: Exception) {
                chatHistory.append(ChatMessage("notify", "⚠ notify error: ${e.javaClass.simpleName}: ${e.message?.take(120)}"))
                pushState()
            }
            val serverSaysSpoken = json.optBoolean("already_spoken", false)
            if (!serverSaysSpoken && !audio.isResponseEcho(message)) {
                audio.speakAlert(title, message)
            }
        }
    }

    private fun isDuplicateNotify(kind: String, title: String, message: String, sessionId: String): Boolean {
        val now = System.currentTimeMillis()
        val windowMs = 10_000L
        while (recentNotifyFingerprints.isNotEmpty() &&
            now - recentNotifyFingerprints.first().first > windowMs
        ) {
            recentNotifyFingerprints.removeFirst()
        }
        val fingerprint = "$kind\u0001$title\u0001$message\u0001$sessionId"
        val dup = recentNotifyFingerprints.any { it.second == fingerprint }
        if (!dup) recentNotifyFingerprints.addLast(now to fingerprint)
        return dup
    }

    private fun showSystemNotification(title: String, message: String, host: String, urgent: Boolean, sessionId: String = "") {
        val notifId = sessionId.hashCode().let { if (it < 0) -it else it } + 100
        val pendingIntent = android.app.PendingIntent.getActivity(
            context,
            notifId,
            // Phase 4: notification tap-through opens the Compose surface
            // (shares the app-scoped ViewModel, so the session chip is
            // pre-selected from the extras below).
            Intent(context, AssistantComposeActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("target_session_id", sessionId)
                putExtra("target_session_title", title)
                putExtra("target_message", message)
            },
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val replyLabel = "Reply to session"
        val replyActionIntent = Intent(context, NotificationReplyReceiver::class.java).apply {
            action = NotificationReplyReceiver.ACTION_REPLY
            putExtra("session_id", sessionId)
            putExtra("session_title", title)
        }
        val replyPendingIntent = android.app.PendingIntent.getBroadcast(
            context,
            notifId + 200,
            replyActionIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
        )
        val replyAction = androidx.core.app.NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            replyLabel,
            replyPendingIntent
        ).addRemoteInput(
            androidx.core.app.RemoteInput.Builder("reply_text").setLabel(replyLabel).build()
        ).build()

        val conversationTitle = if (sessionId.isNotEmpty()) sessionTitleFromNotify(title, sessionId) else ""
        val senderName = if (host.isNotEmpty()) "Hermes ($host)" else "Hermes"
        val messagingStyle = androidx.core.app.NotificationCompat.MessagingStyle("Hermes Assistant")
            .setConversationTitle(conversationTitle.ifEmpty { null })
            .addMessage(message, System.currentTimeMillis(), senderName)

        val builder = androidx.core.app.NotificationCompat.Builder(context, NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_MESSAGE)
            .setContentTitle(title)
            .setContentText(message + if (host.isNotEmpty()) " (from $host)" else "")
            .setStyle(messagingStyle)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(replyAction)
            .setPriority(if (urgent) androidx.core.app.NotificationCompat.PRIORITY_HIGH else androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)

        if (urgent) {
            builder.setDefaults(androidx.core.app.NotificationCompat.DEFAULT_SOUND or androidx.core.app.NotificationCompat.DEFAULT_VIBRATE)
        }
        notificationManager.notify(notifId, builder.build())
    }

    // ------------------------------------------------------------------
    // Reply path + offline queue flush
    // ------------------------------------------------------------------

    private fun handleAssistantReply(message: String) {
       setStatusInternal("Hermes: $message", StatusRingView.State.CONNECTED)
        audio.rememberSpokenResponse(message)
        chatHistory.append(
            ChatMessage("hermes", message, sessionId = activeSessionId, sessionTitle = activeSessionTitle)
        )
        pushState()
    }

    private fun flushQueueIfAny() {
        if (chatHistory.queue.isEmpty()) return
        flushingQueue = true
        sendNextQueued()
    }

    private fun sendNextQueued() {
        val next = chatHistory.popQueued() ?: run {
            flushingQueue = false
           setStatusInternal("All queued messages sent", StatusRingView.State.CONNECTED)
            pushState()
            return
        }
        chatHistory.markQueuedDelivered(next)
       setStatusInternal("Sending queued: ${next.text}", StatusRingView.State.THINKING)
        pushState()
        if (relay.send(next.text)) {
            // Response arrives via onMessage; audio_end triggers the next send
        } else {
            flushingQueue = false
            chatHistory.requeue(next)
           setStatusInternal("Reconnect lost — message re-queued", StatusRingView.State.IDLE)
            pushState()
            connectIfNeeded()
        }
    }

    // ------------------------------------------------------------------
    // Auto-listen policy (A4)
    // ------------------------------------------------------------------

    private fun scheduleAutoListen() {
        if (!lastTurnUserInitiated) {
            startWakeWord()
            return
        }
        if (autoListenScheduled) return
        autoListenScheduled = true
        viewModelScope.launch {
            delay(2500)
            autoListenScheduled = false
            if (voice.isActive || audio.playbackInFlight()) return@launch
            if (_uiState.value.isConnected) beginListening() else startWakeWord()
        }
    }

    // ------------------------------------------------------------------
    // State helpers
    // ------------------------------------------------------------------

    private fun sessionTitleFromNotify(title: String, sessionId: String): String {
        val idx = title.indexOf("·")
        return if (idx > 0) title.substring(idx + 1).trim() else sessionId.takeLast(10)
    }

    private fun updateSpeakLabel(label: String) {
        _uiState.update { it.copy(speakButtonLabel = label) }
    }

    private fun setStatusInternal(text: String, state: StatusRingView.State) {
        _uiState.update {
            it.copy(
                status = text.replace('\n', ' ').replace(Regex("\\s+"), " ").trim(),
                statusState = state,
            )
        }
    }

    /** Push history/sessions/badges into the state snapshot. */
    private fun pushState() {
        _uiState.update { s ->
            // F9: reply badge and queue count can both be true — show both.
            val n = chatHistory.queue.size
            val queueBadge = if (n > 0) "$n message${if (n == 1) "" else "s"} queued — will send when connected" else ""
            val replyBadge = if (replySessionId.isNotEmpty()) {
                "Reply goes to: $replySessionTitle — tap to speak"
            } else ""
            val subTextLabel = when {
                replyBadge.isNotEmpty() && queueBadge.isNotEmpty() ->
                    "$replyBadge · $queueBadge"
                replyBadge.isNotEmpty() -> replyBadge
                queueBadge.isNotEmpty() -> queueBadge
                else -> "Tap to speak · wake word: \"Hey Hermes\""
            }
            s.copy(
                messages = chatHistory.messages,
                sessions = sessionStore.sessions,
                replySessionId = replySessionId,
                replySessionTitle = replySessionTitle,
                queueCount = n,
                voiceActive = voice.isActive,
                hasParkedAudio = audio.pendingAudio() != null,
                subTextLabel = subTextLabel,
            )
        }
    }

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                NOTIFICATION_CHANNEL,
                "Hermes Agent Events",
                android.app.NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when Hermes finishes a session or needs your input"
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    override fun onCleared() {
        relay.cancel()
        audio.shutdown()
        voice.shutdown()
        try { audioOutputStream?.close() } catch (e: Exception) {}
        audioOutputStream = null
        super.onCleared()
    }

    companion object {
        private const val NOTIFICATION_CHANNEL = "hermes_events"
    }
}
