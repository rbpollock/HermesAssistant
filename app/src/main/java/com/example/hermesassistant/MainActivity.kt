package com.example.hermesassistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import okio.ByteString
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Thin wiring layer: binds the UI, delegates networking to [RelayClient],
 * audio to [AudioPlayer], and speech recognition to [VoiceInput], and
 * owns the app-specific glue (history, session chips, notifications,
 * targeted replies, auto-update).
 */
class MainActivity : ComponentActivity() {

    // UI
    private lateinit var statusText: TextView
    private lateinit var subText: TextView
    private lateinit var speakButton: Button
    private lateinit var statusRing: StatusRingView
    private lateinit var historyList: LinearLayout
    private lateinit var historyScroll: android.widget.ScrollView
    private lateinit var typeToggleButton: ImageButton
    private lateinit var textInputRow: LinearLayout
    private lateinit var textInput: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var sessionChipsRow: LinearLayout
    private lateinit var sessionChipsScroll: android.widget.HorizontalScrollView
    private lateinit var settingsButton: ImageButton
    private lateinit var chatSection: View
    private lateinit var assistantPanel: View
    private lateinit var panelCollapseBar: View
    private lateinit var panelToggleButton: ImageButton
    private lateinit var panelToggleLabel: TextView

    private var panelCollapsed = false
    private var navBarInsetBottom = 0

    private lateinit var notificationManager: NotificationManagerCompat
    private lateinit var sessionStore: SessionStore
    private lateinit var chatHistory: ChatHistoryStore

    // Delegates
    private lateinit var relay: RelayClient
    private lateinit var audio: AudioPlayer
    private lateinit var voice: VoiceInput

    // WS state
    private var isConnected = false
    private var pendingMessage: String? = null

    // Audio stream accumulation
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
    // A4: whether the last turn was initiated by the user (voice/typed
    // message) vs. a background notify. Auto-listen only after a
    // user-initiated turn; after a notify the app stays quiet (wake word).
    private var lastTurnUserInitiated = false

    private val serviceReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                HermesForegroundService.ACTION_WAKE_WORD -> onWakeWordHeard()
                HermesForegroundService.ACTION_DICTATION_RESULT -> {
                    val text = intent.getStringExtra(HermesForegroundService.EXTRA_DICTATION_TEXT).orEmpty()
                    val alreadySent = intent.getBooleanExtra(HermesForegroundService.EXTRA_DICTATION_ALREADY_SENT, false)
                    handleDictatedText(text, alreadySent)
                }
                NotificationReplyReceiver.ACTION_HISTORY_UPDATED -> {
                    // An inline reply from the shade appended to the shared
                    // chat_history.json — reload so it shows up here.
                    chatHistory.reload()
                    renderHistory()
                }
                HermesForegroundService.ACTION_REPLY_READY -> {
                    // A wake-word HTTP reply landed. The service appended it
                    // to the shared history file AND spoke it (BT-gated) —
                    // we refresh the view and status without re-appending
                    // (reload would duplicate) and without re-speaking.
                    val replyText = intent.getStringExtra(HermesForegroundService.EXTRA_REPLY_TEXT).orEmpty()
                    if (replyText.isNotEmpty()) {
                        audio.rememberSpokenResponse(replyText)
                        setStatus("Hermes: $replyText", StatusRingView.State.CONNECTED)
                    }
                    chatHistory.reload()
                    renderHistory()
                }
            }
        }
    }

    companion object {
        private const val NOTIFICATION_CHANNEL = "hermes_events"
        private const val REQ_POST_NOTIFICATIONS = 2
        private const val REQ_RECORD_AUDIO = 3
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        notificationManager = NotificationManagerCompat.from(this)
        sessionStore = SessionStore(this)
        chatHistory = ChatHistoryStore(this)

        relay = RelayClient(this)
        audio = AudioPlayer(this)
        voice = VoiceInput(this)

        wireRelay()
        wireAudio()
        wireVoice()

        createNotificationChannel()
        startHermesForegroundService()
        audio.initTts()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_RECORD_AUDIO)
        } else {
            // Mic already granted — make sure the service wake word is up
            // (it may have started before the service finished loading).
            HermesForegroundService.notifyMicPermissionGranted()
        }
        requestNotificationPermissionIfNeeded()

        wireTextInput()
        wireSessionChips()
        wirePanelToggle()
        wireSettings()

        // Make the collapsed band clear the navigation bar from the start.
        panelCollapseBar.post { updateNavBarInset() }

        // Auto-start listening whenever the activity loads. The speak
        // button becomes "TAP TO CANCEL" while listening, so the user can
        // always stop it. (Voice-invocation intents hit this same path.)
        beginListening()

        // Auto-update: the "Update" notification action lands here.
        if (isUpdateAction(intent?.action)) {
            handleUpdateAction()
        }

        // Notification tap: select the session chip for that notification.
        handleTargetSessionIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent) // keep getIntent() in sync for singleTask relaunch
        if (isVoiceInvocation(intent.action)) {
            beginListening()
        }
        if (isUpdateAction(intent.action)) {
            handleUpdateAction()
        }
        handleTargetSessionIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        val filter = android.content.IntentFilter().apply {
            addAction(HermesForegroundService.ACTION_WAKE_WORD)
            addAction(HermesForegroundService.ACTION_DICTATION_RESULT)
            addAction(HermesForegroundService.ACTION_REPLY_READY)
            addAction(NotificationReplyReceiver.ACTION_HISTORY_UPDATED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serviceReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(serviceReceiver, filter)
        }
        // Inline replies from the shade may have appended to history while
        // we were in the background — pick them up now.
        chatHistory.reload()
        renderHistory()

        // If the compact overlay was showing (wake word heard while another
        // app was foreground), remove it now that the full app is visible.
        HermesForegroundService.hideOverlayIfShown()

        // Update check on every foreground (throttled to 1h internally).
        UpdateChecker.checkAndNotify(this)

        connectIfNeeded()
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(serviceReceiver) } catch (e: Exception) { /* not registered */ }
    }

    override fun onDestroy() {
        super.onDestroy()
        relay.cancel()
        audio.shutdown()
        voice.shutdown()
        try { audioOutputStream?.close() } catch (e: Exception) {}
        audioOutputStream = null
    }

    // ------------------------------------------------------------------
    // View binding
    // ------------------------------------------------------------------

    private fun bindViews() {
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
        chatSection = findViewById(R.id.chatSection)
        assistantPanel = findViewById(R.id.assistantPanel)
        panelCollapseBar = findViewById(R.id.panelCollapseBar)
        panelToggleButton = findViewById(R.id.panelToggleButton)
        panelToggleLabel = findViewById(R.id.panelToggleLabel)
    }

    // ------------------------------------------------------------------
    // Delegate wiring
    // ------------------------------------------------------------------

    private fun wireRelay() {
        relay.attach(object : RelayClient.Listener {
            override fun onConnected() {
                isConnected = true
                setStatus("Connected to server", StatusRingView.State.CONNECTED)
                pendingMessage?.let {
                    if (relay.send(it)) pendingMessage = null
                }
                flushQueueIfAny()
            }

            override fun onDisconnected(reason: String) {
                isConnected = false
                setStatus(reason, StatusRingView.State.IDLE)
            }

            override fun onTextResponse(message: String) {
                // A5: single unified reply path (history + status + echo
                // tracking). Audio for WS replies arrives separately as
                // streamed chunks (A2).
                handleAssistantReply(message)
            }

            override fun onStatus(message: String) {
                setStatus(message, StatusRingView.State.THINKING)
            }

            override fun onNotify(json: JSONObject) {
                handleNotify(json)
            }

            override fun onAudioBytes(bytes: ByteString) {
                // Progressive playback (A2): the first chunk starts the
                // stream so audio begins as soon as data flows, not at
                // audio_end. Bytes are ALSO buffered to a temp file so a
                // pipe failure can fall back to whole-file playback.
                if (audioOutputStream == null) {
                    audioTempFile = File(cacheDir, "stream_${System.currentTimeMillis()}.mp3")
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
                audioOutputStream?.close()
                audioOutputStream = null
                audio.streamEnd()
                val streamOk = !audio.streamFailed()
                if (streamOk) {
                    // Pipe path handled playback — nothing more to do.
                    setStatus("Speaking...", StatusRingView.State.SPEAKING)
                } else {
                    // Pipe failed: fall back to the buffered file.
                    audioTempFile?.let { audio.playAudio(it) }
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
                setStatus("Speaking...", StatusRingView.State.SPEAKING)
            }

            override fun onSpeakingStopped() {}

            override fun onStatus(message: String) {
                setStatus(message, StatusRingView.State.CONNECTED)
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
                    VoiceInput.State.DICTATION -> speakButton.text = "TAP TO CANCEL"
                    VoiceInput.State.IDLE -> speakButton.text = "LISTENING FOR WAKE WORD"
                }
            }

            override fun onError(message: String) {
                ChimePlayer.playStop(this@MainActivity)
                if (!isConnected) {
                    // Offline: fall back to Vosk dictation
                    startOfflineDictation()
                } else {
                    setStatus("$message. Try again.", StatusRingView.State.IDLE)
                    startWakeWord()
                }
            }

            override fun onListening() {
                ChimePlayer.playStart(this@MainActivity)
                expandPanel()
            }

            override fun onStoppedListening() {
                ChimePlayer.playStop(this@MainActivity)
            }

            override fun onThinking() {
                setStatus("Thinking...", StatusRingView.State.THINKING)
            }
        })
    }

    // ------------------------------------------------------------------
    // Listening flow (orchestrates service wake word + VoiceInput)
    // ------------------------------------------------------------------

    /** Free the mic and start Google STT (or Vosk when offline). */
    private fun beginListening() {
        stopWakeWordForStt()
        expandPanel()
        if (isConnected) {
            voice.startListening()
        } else {
            voice.startOfflineDictation()
        }
    }

    private fun startOfflineDictation() {
        expandPanel()
        voice.startOfflineDictation()
    }

    /** The service heard "Hey Hermes" — take over from the wake word. */
    private fun onWakeWordHeard() {
        runOnUiThread {
            expandPanel()
            beginListening()
        }
    }

    /** Back to wake-word mode (used after errors / cancelled listens). */
    private fun startWakeWord() {
        HermesForegroundService.startWakeWordNow()
        speakButton.text = "LISTENING FOR WAKE WORD"
        setStatus("Listening for \"Hey Hermes\"", StatusRingView.State.IDLE)
    }

    private fun stopWakeWordForStt() {
        HermesForegroundService.stopWakeWord()
    }

    /** A full phrase was captured by Vosk while offline — queue it. */
    private fun handleDictatedText(hypothesis: String?, alreadySent: Boolean = false) {
        val text = (hypothesis ?: "").trim()
        voice.onDictationResult(text)

        if (text.isNotEmpty()) {
            // Strip a leading wake word if the model caught it
            val clean = text
                .replaceFirst("(?i)^(hey )?hermes[\\s,.-]*".toRegex(), "")
                .trim()
            if (clean.isNotEmpty() && !alreadySent) {
                // Only enqueue when the service did NOT already POST the
                // phrase (wake-word path sends it directly over HTTP).
                chatHistory.enqueue(clean)
                renderHistory()
                updateQueueBadge()
                setStatus("Offline — queued: $clean", StatusRingView.State.CONNECTED)
            } else {
                // Already sent by the service — just reflect it in history.
                chatHistory.append(ChatMessage("user", clean))
                renderHistory()
            }
        }

        // Keep trying to reach the server so the queue flushes
        connectIfNeeded()

        // Back to wake-word listening
        startWakeWord()
    }

    private fun connectIfNeeded() {
        if (!relay.isConnected) relay.connect()
    }

    // ------------------------------------------------------------------
    // Sending
    // ------------------------------------------------------------------

    /**
     * A5: the single place a Hermes reply enters the UI — history bubble,
     * status line, and echo tracking. Both the WS text frame and (via the
     * service's ACTION_REPLY_READY handler) the wake-word HTTP reply feed
     * this path, so every reply is treated identically.
     */
    private fun handleAssistantReply(message: String) {
        setStatus("Hermes: $message", StatusRingView.State.CONNECTED)
        audio.rememberSpokenResponse(message)
        chatHistory.append(
            ChatMessage("hermes", message, sessionId = activeSessionId, sessionTitle = activeSessionTitle)
        )
        renderHistory()
    }

    /** Shared send path for both voice and typed text. */
    private fun sendUserMessage(rawText: String) {
        val userText = rawText.trim()
        if (userText.isEmpty()) return

        // A4: this was a user-initiated turn — auto-listen may follow.
        lastTurnUserInitiated = true

        activeSessionId = replySessionId
        activeSessionTitle = replySessionTitle

        chatHistory.append(
            ChatMessage("user", userText, sessionId = activeSessionId, sessionTitle = activeSessionTitle)
        )
        renderHistory()
        setStatus("You: $userText", StatusRingView.State.THINKING)

        val payload = if (replySessionId.isNotEmpty()) {
            "{\"message\": ${JSONObject.quote(userText)}, \"session_id\": ${JSONObject.quote(replySessionId)}}"
        } else {
            userText
        }

        if (relay.send(payload)) {
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
            connectIfNeeded()
        }
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
        if (relay.send(next.text)) {
            // Response will arrive via onMessage; audio_end triggers the next send
        } else {
            // Send failed — put it back at the front and try again later
            flushingQueue = false
            chatHistory.requeue(next)
            renderHistory()
            updateQueueBadge()
            setStatus("Reconnect lost — message re-queued", StatusRingView.State.IDLE)
            connectIfNeeded()
        }
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

        // Short-window dedupe for identical notify events (reconnect replay).
        val isDuplicate = isDuplicateNotify(kind, title, message, sessionId)

        // A "Delivered to live session" confirmation: mark the newest user
        // bubble for that session with the green check, don't spam a
        // notification for it.
        if (json.optString("event") == "injected") {
            chatHistory.markInjected(sessionId)
            renderHistory()
            return
        }

        // Remember which session this came from so the next spoken message
        // can be routed back to it (answering a clarify prompt, etc.).
        if (sessionId.isNotEmpty()) {
            replySessionId = sessionId
            replySessionTitle = if (sessionId.isNotEmpty()) sessionTitleFromNotify(title, sessionId) else ""
        }

        chatHistory.append(
            ChatMessage(
                "notify",
                message,
                sessionId = sessionId,
                sessionTitle = if (sessionId.isNotEmpty()) sessionTitleFromNotify(title, sessionId) else "",
            )
        )
        renderHistory()

        // A4: an incoming notify is NOT a user-initiated turn — keep the
        // app quiet afterwards (no auto-listen chime spam). The WS text
        // reply to OUR message already arrived via onTextResponse and
        // marked the turn as user-initiated; don't let the trailing
        // "Hermes finished" notify flip it back to quiet before the
        // auto-listen fires.
        val isReplyToOwnTurn = json.optBoolean("already_spoken", false) ||
            audio.isResponseEcho(message) ||
            json.optString("event") == "injected"
        if (!isReplyToOwnTurn) {
            lastTurnUserInitiated = false
        }

        val urgent = kind == "question" || kind == "approval"
        if (!isDuplicate) {
            try {
                showSystemNotification(title, message, host, urgent, sessionId)
            } catch (e: Exception) {
                // A notification failure must not kill the message pipeline.
                chatHistory.append(ChatMessage("notify", "⚠ notify error: ${e.javaClass.simpleName}: ${e.message?.take(120)}"))
                renderHistory()
            }

            // Speak the alert ONLY when a Bluetooth device is connected and
            // the server didn't flag it as already-spoken response audio
            // (echo/chorus fix), and it's not the response text we just played.
            val serverSaysSpoken = json.optBoolean("already_spoken", false)
            if (!serverSaysSpoken && !audio.isResponseEcho(message)) {
                audio.speakAlert(title, message)
            }
        }
    }

    /** Short-window dedupe for identical notify events (reconnect replay). */
    private val recentNotifyFingerprints = ArrayDeque<Pair<Long, String>>()
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
            this,
            notifId,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("target_session_id", sessionId)
                putExtra("target_session_title", title)
                putExtra("target_message", message)
            },
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        // Reply action — the RemoteInput carries the text back through
        // NotificationReplyReceiver.
        val replyLabel = "Reply to session"
        val replyActionIntent = Intent(this, NotificationReplyReceiver::class.java).apply {
            action = NotificationReplyReceiver.ACTION_REPLY
            putExtra("session_id", sessionId)
            putExtra("session_title", title)
        }
        val replyPendingIntent = android.app.PendingIntent.getBroadcast(
            this,
            notifId + 200,
            replyActionIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
        )
        val replyAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            replyLabel,
            replyPendingIntent
        ).addRemoteInput(
            androidx.core.app.RemoteInput.Builder("reply_text").setLabel(replyLabel).build()
        ).build()

        // Android Auto: messaging style so the reply chain surfaces in the
        // car and voice replies work through the head unit.
        val conversationTitle = if (sessionId.isNotEmpty()) sessionTitleFromNotify(title, sessionId) else ""
        val senderName = if (host.isNotEmpty()) "Hermes ($host)" else "Hermes"
        val messagingStyle = NotificationCompat.MessagingStyle("Hermes Assistant")
            .setConversationTitle(conversationTitle.ifEmpty { null })
            .addMessage(message, System.currentTimeMillis(), senderName)

        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentTitle(title)
            .setContentText(message + if (host.isNotEmpty()) " (from $host)" else "")
            .setStyle(messagingStyle)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(replyAction)
            .setPriority(if (urgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)

        if (urgent) {
            builder.setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)
        }
        notificationManager.notify(notifId, builder.build())
    }

    // ------------------------------------------------------------------
    // Session chips + history
    // ------------------------------------------------------------------

    private fun handleTargetSessionIntent(intent: Intent?) {
        val sessionId = intent?.getStringExtra("target_session_id").orEmpty()
        if (sessionId.isEmpty()) return
        val title = intent?.getStringExtra("target_session_title").orEmpty()
        sessionStore.upsert(KnownSession(id = sessionId, title = sessionTitleFromNotify(title, sessionId)))
        replySessionId = sessionId
        replySessionTitle = title
        updateReplyBadge()
        renderSessionChips()
        val msg = intent?.getStringExtra("target_message").orEmpty()
        if (msg.isNotEmpty()) {
            chatHistory.append(ChatMessage("notify", "$title — $msg"))
            renderHistory()
        }
    }

    private fun sessionTitleFromNotify(title: String, sessionId: String): String {
        // "Hermes finished · <session title>" -> "<session title>"
        val idx = title.indexOf("·")
        return if (idx > 0) title.substring(idx + 1).trim() else sessionId.takeLast(10)
    }

    private val sessionPalette = intArrayOf(
        0xFF60A5FA.toInt(), // blue
        0xFF34D399.toInt(), // green
        0xFFFBBF24.toInt(), // amber
        0xFFF472B6.toInt(), // pink
        0xFFA78BFA.toInt(), // violet
        0xFFF87171.toInt(), // red
        0xFF2DD4BF.toInt(), // teal
        0xFFFB923C.toInt(), // orange
        0xFFA3E635.toInt(), // lime
        0xFF38BDF8.toInt(), // sky
    )

    private fun sessionColor(sessionId: String): Int {
        if (sessionId.isEmpty()) return sessionPalette[0]
        return sessionPalette[Math.floorMod(sessionId.hashCode(), sessionPalette.size)]
    }

    private fun renderSessionChips() {
        sessionChipsRow.removeAllViews()
        if (sessionStore.sessions.isEmpty()) {
            sessionChipsRow.visibility = View.GONE
            return
        }
        sessionChipsRow.visibility = View.VISIBLE
        sessionStore.sessions.forEach { session ->
            val selected = session.id == replySessionId
            val color = sessionColor(session.id)
            val chip = TextView(this).apply {
                text = session.title.ifEmpty { "…${session.id.takeLast(10)}" }
                textSize = 12f
                setTextColor(if (selected) 0xFF0D0F14.toInt() else 0xFFE5E7EB.toInt())
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = chipBackground(selected, color)
                isClickable = true
                setOnClickListener {
                    replySessionId = if (selected) "" else session.id
                    replySessionTitle = if (selected) "" else session.title
                    updateReplyBadge()
                    renderSessionChips()
                }
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
            sessionChipsRow.addView(chip, lp)
        }
        sessionChipsScroll.post {
            // Scroll the selected chip into view so the user sees what's armed
            val selIndex = sessionStore.sessions.indexOfFirst { it.id == replySessionId }
            if (selIndex >= 0 && selIndex < sessionChipsRow.childCount) {
                val child = sessionChipsRow.getChildAt(selIndex)
                sessionChipsScroll.smoothScrollTo(child.left, 0)
            }
        }
    }

    private fun chipBackground(selected: Boolean, color: Int) = GradientDrawable().apply {
        cornerRadius = dp(18).toFloat()
        if (selected) {
            setColor(color)
        } else {
            setColor(0xFF111827.toInt())
            setStroke(dp(1), color)
        }
    }

    private fun renderHistory() {
        historyList.removeAllViews()
        chatHistory.messages.forEach { addBubble(it) }
        historyScroll.post { historyScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun addBubble(m: ChatMessage) {
        val bubbleMaxWidth = (resources.displayMetrics.widthPixels * 0.8f).toInt()
        val sessionAccent = sessionColor(m.sessionId)
        val tv = TextView(this).apply {
            // Injected confirmation: a small GREEN check after the user's
            // text signals the message was delivered into a live session.
            val suffix = if (m.queued) "\n\u23F3 queued (offline)" else ""
            val base = m.text + suffix
            if (m.role == "user" && m.injected) {
                val spannable = android.text.SpannableString(base + "  \u2714")
                spannable.setSpan(
                    android.text.style.ForegroundColorSpan(0xFF34D399.toInt()),
                    base.length,
                    spannable.length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                text = spannable
            } else {
                text = base
            }
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
        ).apply { topMargin = dp(10) }

        when (m.role) {
            "user" -> {
                bg.setColor(if (m.queued) 0xFF3B2F1A.toInt() else 0xFF16233D.toInt())
                lp.gravity = android.view.Gravity.END
            }
            "hermes" -> {
                bg.setColor(0xFF1E293B.toInt())
                lp.gravity = android.view.Gravity.START
            }
            else -> { // notify
                bg.setColor(0xFF111827.toInt())
                lp.gravity = android.view.Gravity.CENTER
                tv.setTextColor(0xFF93C5FD.toInt())
                tv.textSize = 13f
            }
        }
        bg.setStroke(if (m.role == "notify") dp(1) else dp(2), sessionAccent)
        tv.background = bg
        historyList.addView(tv, lp)
    }

    private fun selectSessionFromMessage(m: ChatMessage) {
        val targetId = m.sessionId
        if (targetId.isNotEmpty()) {
            val targetTitle = m.sessionTitle.ifEmpty { "…${targetId.takeLast(10)}" }
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

    private fun updateReplyBadge() {
        subText.text = if (replySessionId.isNotEmpty()) {
            "Reply goes to: $replySessionTitle — tap to speak"
        } else {
            updateQueueBadgeText()
        }
    }

    private fun updateQueueBadge() {
        subText.text = updateQueueBadgeText()
    }

    private fun updateQueueBadgeText(): String {
        val n = chatHistory.queue.size
        return if (n > 0) {
            "$n message${if (n == 1) "" else "s"} queued — will send when connected"
        } else {
            "Tap to speak · wake word: \"Hey Hermes\""
        }
    }

    // ------------------------------------------------------------------
    // Panel collapse/expand + nav bar inset
    // ------------------------------------------------------------------

    private fun wirePanelToggle() {
        // Down/up chevron band: collapse the listening panel so the chat
        // takes the whole screen; tap again to expand. Whole band tappable.
        panelCollapseBar.setOnClickListener { togglePanelCollapsed() }
        panelToggleButton.setOnClickListener { togglePanelCollapsed() }
        panelCollapseBar.post { updateNavBarInset() }
    }

    private fun togglePanelCollapsed() {
        panelCollapsed = !panelCollapsed
        applyPanelCollapsed()
    }

    private fun applyPanelCollapsed() {
        assistantPanel.visibility = if (panelCollapsed) View.GONE else View.VISIBLE
        panelToggleButton.setImageResource(
            if (panelCollapsed) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down
        )
        panelToggleLabel.text = if (panelCollapsed) "Expand panel" else "Collapse panel"
        // When collapsed, the chat section gets ~5% bottom padding so the
        // expand band floats above the bottom of the chat content.
        val extra = if (panelCollapsed) {
            (resources.displayMetrics.heightPixels * 0.05f).toInt()
        } else 0
        chatSection.setPadding(0, 0, 0, extra)
        // Re-read the nav bar inset (may have changed with the panel state).
        updateNavBarInset()
    }

    private fun updateNavBarInset() {
        val inset = ViewCompatRootInsets()
        navBarInsetBottom = inset
        // Only pad above the nav bar when collapsed (the band is at the
        // bottom edge then); expanded, the panel already fills below.
        panelCollapseBar.setPadding(0, 0, 0, if (panelCollapsed) navBarInsetBottom else 0)
    }

    private fun ViewCompatRootInsets(): Int {
        return try {
            val insets = androidx.core.view.ViewCompat.getRootWindowInsets(panelCollapseBar)
            insets?.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
        } catch (e: Exception) {
            0
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) updateNavBarInset()
    }

    private fun expandPanel() {
        if (panelCollapsed) {
            panelCollapsed = false
            applyPanelCollapsed()
        }
    }

    // ------------------------------------------------------------------
    // Text input + settings
    // ------------------------------------------------------------------

    private fun wireTextInput() {
        speakButton.setOnClickListener {
            when {
                voice.isActive -> voice.cancel()
                audio.playbackInFlight() -> Unit // ignore taps mid-playback
                !isConnected -> startOfflineDictation()
                else -> beginListening()
            }
        }

        val iconSize = (resources.displayMetrics.widthPixels * 0.12f).toInt()
        typeToggleButton.layoutParams = typeToggleButton.layoutParams.apply { width = iconSize; height = iconSize }
        typeToggleButton.setOnClickListener {
            val visible = textInputRow.visibility == View.VISIBLE
            textInputRow.visibility = if (visible) View.GONE else View.VISIBLE
            if (visible) {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.hideSoftInputFromWindow(textInput.windowToken, 0)
            } else {
                textInput.requestFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.showSoftInput(textInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }

        sendButton.setOnClickListener { sendTypedText() }
        textInput.setOnEditorActionListener { _, _, _ ->
            sendTypedText()
            true
        }
    }

    private fun sendTypedText() {
        val text = textInput.text.toString().trim()
        if (text.isEmpty()) return
        textInput.setText("")
        sendUserMessage(text)
    }

    private fun wireSessionChips() {
        sessionChipsScroll.setOnClickListener {
            // Tapping the chips area expands the panel if collapsed
            expandPanel()
        }
    }

    private fun wireSettings() {
        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    // ------------------------------------------------------------------
    // Permissions / notifications / updates
    // ------------------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                NOTIFICATION_CHANNEL,
                "Hermes Agent Events",
                android.app.NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when Hermes finishes a session or needs your input"
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun startHermesForegroundService() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(Intent(this, HermesForegroundService::class.java))
            } else {
                startService(Intent(this, HermesForegroundService::class.java))
            }
        } catch (e: Exception) {
            // BackgroundServiceStartNotAllowedException — start next launch.
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_POST_NOTIFICATIONS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                HermesForegroundService.notifyMicPermissionGranted()
            }
        }
    }

    private fun isVoiceInvocation(action: String?): Boolean {
        return action == Intent.ACTION_ASSIST ||
            action == Intent.ACTION_VOICE_COMMAND ||
            action == "com.example.hermesassistant.START_LISTENING"
    }

    private fun isUpdateAction(action: String?): Boolean {
        return action == UpdateChecker.ACTION_UPDATE
    }

    private fun handleUpdateAction() {
        val apkUrl = intent.getStringExtra("update_apk_url").orEmpty()
        if (apkUrl.isEmpty()) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.RELEASE_PAGE_URL)))
            return
        }
        if (!UpdateChecker.canRequestInstalls(this)) {
            setStatus("Allow installs from this app, then retry", StatusRingView.State.CONNECTED)
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.RELEASE_PAGE_URL)))
            }
            return
        }
        setStatus("Downloading update...", StatusRingView.State.THINKING)
        Thread {
            val result = UpdateChecker.installRelease(this, UpdateChecker.ReleaseInfo(
                versionName = intent.getStringExtra("update_version").orEmpty(),
                apkUrl = apkUrl,
            ))
            runOnUiThread { setStatus(result, StatusRingView.State.CONNECTED) }
        }.start()
    }

    private fun scheduleAutoListen() {
        // A4: only auto-listen after a turn the USER initiated. After a
        // background notify (someone else's session finished), stay quiet
        // and just leave the wake word armed — no surprise chime cycles.
        if (!lastTurnUserInitiated) {
            startWakeWord()
            return
        }
        if (autoListenScheduled) return
        autoListenScheduled = true
        // Longer quiet delay (A4): let the user absorb the reply before
        // the mic re-opens; 700ms was too aggressive and caused chime spam.
        statusText.postDelayed({
            autoListenScheduled = false
            if (voice.isActive || audio.playbackInFlight()) return@postDelayed
            // Offline: return to wake-word mode rather than auto-dictating
            if (isConnected) beginListening() else startWakeWord()
        }, 2500)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun setStatus(text: String, state: StatusRingView.State) {
        val oneLine = text.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        statusText.text = oneLine
        statusRing.state = state
    }
}
