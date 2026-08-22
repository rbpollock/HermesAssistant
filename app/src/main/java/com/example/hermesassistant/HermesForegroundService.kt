package com.example.hermesassistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import okhttp3.OkHttpClient
import java.util.Locale
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Keeps the app process alive AND runs the always-on Vosk wake word.
 *
 * Owning Vosk here (instead of MainActivity) means "Hey Hermes" keeps
 * working while the app is in the background — the microphone stays
 * open inside the foreground service instead of being released when
 * the Activity is paused.
 *
 * Modes:
 * - WAKE WORD (default): constrained grammar `["hey hermes","[unk]"]`
 *   so detection is reliable (the small model free-forms "hermes" into
 *   "herman"/"hermis" etc.). NO_TIMEOUT — listens forever.
 * - DICTATION: free-form recognizer for offline transcription; the
 *   result is broadcast back to MainActivity for the queue.
 *
 * Mic handoff: MainActivity must call stopWakeWord() before using the
 * Google speech recognizer (only one thing can hold the mic), and
 * resumeWakeWord() afterwards.
 */
class HermesForegroundService : Service(), RecognitionListener {

    companion object {
        const val CHANNEL_ID = "hermes_service"
        // Foreground-service notification ID + PendingIntent request code.
        // Kept in a reserved range so session notify/reply notifications
        // (IDs 100+ / 200+) never collide with it.
        const val NOTIFICATION_ID = 1
        const val REQUEST_CODE = 100000

        // Broadcasts from this service -> MainActivity
        const val ACTION_WAKE_WORD = "com.example.hermesassistant.WAKE_WORD"
        const val ACTION_DICTATION_RESULT = "com.example.hermesassistant.DICTATION_RESULT"
        const val EXTRA_DICTATION_TEXT = "dictation_text"
        // Set when the wake word path already POSTed the phrase to the
        // relay; MainActivity skips re-enqueueing it (double-send guard).
        const val EXTRA_DICTATION_ALREADY_SENT = "dictation_already_sent"
        // A wake-word HTTP reply landed (history + audio). MainActivity
        // refreshes its history view when it receives this.
        const val ACTION_REPLY_READY = "com.example.hermesassistant.REPLY_READY"
        const val EXTRA_REPLY_TEXT = "reply_text"

        // Long silence tolerance for dictation (ms). Vosk's recognizer
        // ends an utterance after its internal end-of-speech silence;
        // the SpeechService timeout is the outer cap so a pause mid-
        // thought doesn't cut the phrase short.
        const val DICTATION_TIMEOUT_MS = 30_000

        // Command actions sent by MainActivity (same package, so private)
        const val ACTION_STOP_WAKE_WORD = "com.example.hermesassistant.ACTION_STOP_WAKE_WORD"
        const val ACTION_START_DICTATION = "com.example.hermesassistant.ACTION_START_DICTATION"
        const val ACTION_RESUME_WAKE_WORD = "com.example.hermesassistant.ACTION_RESUME_WAKE_WORD"
        const val ACTION_MIC_PERMISSION_GRANTED = "com.example.hermesassistant.ACTION_MIC_PERMISSION_GRANTED"

        @Volatile
        private var instance: HermesForegroundService? = null
        @Volatile
        private var appContext: android.content.Context? = null

        /** Static entry points used by MainActivity. If the service isn't
         *  running yet (fresh process), fall back to a startService command —
         *  the service picks it up in onStartCommand. */
        fun startWakeWordNow() {
            val svc = instance
            if (svc != null) svc.startWakeWordNow()
            else command(ACTION_RESUME_WAKE_WORD)
        }
        fun stopWakeWord() {
            val svc = instance
            if (svc != null) svc.stopWakeWord()
            else command(ACTION_STOP_WAKE_WORD)
        }
        fun startDictation() {
            val svc = instance
            if (svc != null) svc.startDictation()
            else command(ACTION_START_DICTATION)
        }
        fun notifyMicPermissionGranted() {
            val svc = instance
            if (svc != null) {
                svc.micPermissionGranted = true
                svc.resumeWakeWord()
            } else {
                command(ACTION_MIC_PERMISSION_GRANTED)
            }
        }

        /** Hide the compact overlay when the full activity comes forward. */
        fun hideOverlayIfShown() {
            instance?.overlay?.hide()
        }

        private fun command(action: String) {
            val ctx = appContext ?: return
            val i = Intent(ctx, HermesForegroundService::class.java).setAction(action)
            ContextCompat.startForegroundService(ctx, i)
        }
        fun setInstance(s: HermesForegroundService?) { instance = s }
        fun setAppContext(c: android.content.Context) { appContext = c.applicationContext }
    }

    private var voskModel: Model? = null
    private var voskService: SpeechService? = null
    private var dictationMode = false
    private var dictationFromWakeWord = false
    private var micPermissionGranted = false

    // "Over other apps" compact panel. The Compose AssistantOverlay is
    // created lazily (overlay) — it replaces the legacy XML panel fields
    // that were removed in the Compose redesign cleanup.

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        setInstance(this)
        setAppContext(this)
        micPermissionGranted = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        // Kick off model unpacking so the wake word is ready quickly.
        // startWakeWordNow() runs once the model load callback fires.
        initVosk()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        when (intent?.action) {
            ACTION_STOP_WAKE_WORD -> stopWakeWord()
            ACTION_START_DICTATION -> startDictation()
            ACTION_RESUME_WAKE_WORD -> resumeWakeWord()
            ACTION_MIC_PERMISSION_GRANTED -> {
                micPermissionGranted = true
                resumeWakeWord()
            }
            // Plain start (no action): make sure the wake word is up when
            // permission is already granted (fresh process / reboot).
            null -> if (micPermissionGranted) startWakeWordNow()
        }
        // Check for updates on service start (throttled) so the update
        // notification appears even when the app is only running as the
        // foreground service and MainActivity never comes to the front.
        UpdateChecker.checkAndNotify(this)
        return START_STICKY
    }

    fun startWakeWordNow() {
        if (!micPermissionGranted) return
        if (voskModel == null) {
            // Model still unpacking — the load callback starts listening.
            initVosk()
            return
        }
        stopVoskService()
        dictationMode = false
        try {
            // Constrained grammar = reliable wake word. "[unk]" absorbs
            // any other speech so the recognizer doesn't just return
            // empty hypotheses while the user talks to someone else.
            val recognizer = Recognizer(voskModel, 16000.0f, "[\"hey hermes\", \"[unk]\"]")
            voskService = SpeechService(recognizer, 16000.0f)
            // NO_TIMEOUT (-1 default): keep listening until the wake word
            // is heard or stopWakeWord() is called.
            voskService?.startListening(this)
            updateNotification("Listening for \"Hey Hermes\"")
        } catch (e: Exception) {
            updateNotification("Vosk error: ${e.message?.take(60)}")
        }
    }

    fun stopWakeWord() {
        stopVoskService()
    }

    fun resumeWakeWord() {
        startWakeWordNow()
    }

    fun startDictation() {
        if (!micPermissionGranted) return
        if (voskModel == null) {
            // Model still unpacking — start wake word; MainActivity can
            // retry dictation, or the pending dictation flag is handled
            // by the caller falling back to wake word.
            initVosk()
            return
        }
        stopVoskService()
        dictationMode = true
        try {
            // Free-form recognizer for dictation.
            val recognizer = Recognizer(voskModel, 16000.0f)
            voskService = SpeechService(recognizer, 16000.0f)
            // Long outer cap so a mid-thought pause doesn't cut the phrase.
            voskService?.startListening(this, DICTATION_TIMEOUT_MS)
            if (dictationFromWakeWord) {
                updateNotification("Listening...")
            } else {
                updateNotification("Dictating (offline)")
            }
        } catch (e: Exception) {
            updateNotification("Vosk error: ${e.message?.take(60)}")
        }
    }

    private fun stopVoskService() {
        voskService?.stop()
        // CRITICAL: stop() only halts the recognizer THREAD; the
        // AudioRecord created in the SpeechService constructor stays
        // allocated until shutdown() releases it. Without this, the mic
        // remains held when the wake word triggers Google STT, which then
        // fails instantly (the 'flash' back to wake-word mode).
        voskService?.shutdown()
        voskService = null
    }

    private fun initVosk() {
        StorageService.unpack(
            this, "model", "model",
            { model: Model ->
                voskModel = model
                // Permission may arrive after the model; re-check.
                if (micPermissionGranted && voskService == null) {
                    startWakeWordNow()
                }
            },
            { exception ->
                // Show the REAL reason (e.g. missing assets/model/uuid used
                // to make unpack throw FileNotFoundException) instead of a
                // blanket label.
                updateNotification("Wake word model failed: ${exception.message?.take(80)}")
            }
        )
    }

    // --- RecognitionListener (wake word mode) ---
    // NOTE: only onResult (the FINAL, silence-confirmed hypothesis) may
    // trigger the wake word. onPartialResult hypotheses are speculative —
    // with the constrained grammar the recognizer can emit a partial
    // "hey hermes" for phonetically-similar speech ("hey Herman", "hey
    // H", TV/background noise), which caused false activations.

    override fun onPartialResult(hypothesis: String?) {
        // Never trigger from a partial. Wake word fires only on the final
        // confirmed result, so background speech can't accidentally wake it.
    }

    override fun onResult(hypothesis: String?) {
        if (dictationMode) {
            // Offline transcription complete — hand the text to MainActivity
            val text = (hypothesis ?: "").trim()
            stopVoskService()
            ChimePlayer.playStop(this)
            if (text.isNotEmpty()) {
                // Broadcast so MainActivity can show it in history.
                val i = Intent(ACTION_DICTATION_RESULT)
                    .setPackage(packageName)
                    .putExtra(EXTRA_DICTATION_TEXT, text)
                    // When the wake word triggered this, the service already
                    // POSTed the phrase to the relay — MainActivity must not
                    // ALSO enqueue it (double-send). Only the history reload
                    // is wanted in that case.
                    .putExtra(EXTRA_DICTATION_ALREADY_SENT, dictationFromWakeWord)
                sendBroadcast(i)

                // Wake-word path: MainActivity may be paused/dead (the
                // overlay is up over another app), so the broadcast can be
                // lost. Send the phrase over HTTP ourselves — this is the
                // hands-free channel that works from the background.
                if (dictationFromWakeWord) {
                    if (ServerConfig.gatewayMode(this)) {
                        // Gateway mode: hand off to the app's gateway client
                        // via the process-wide bridge (works with a dead
                        // activity too). The DICTATION_RESULT broadcast
                        // above already carries alreadySent=true, so the
                        // activity appends the bubble without re-sending.
                        GatewayBridge.submit?.invoke(text)
                    } else {
                        sendDictatedTextHttp(text)
                    }
                }
            }
            // Back to wake word
            dictationMode = false
            dictationFromWakeWord = false
            startWakeWordNow()
            return
        }
        if (isWakeWordMatch(hypothesis)) {
            wakeWordHeard()
        }
    }

    /** POST a wake-word-dictated phrase to the relay (hands-free path). */
    private fun sendDictatedTextHttp(text: String) {
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build()
            val json = JSONObject()
                .put("message", text)
                .put("session_id", "")
            val body = json.toString()
                .toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
            val request = Request.Builder()
                .url("${ServerConfig.httpBase(this)}/chat/message")
                .post(body)
                .build()
            Thread {
                try {
                    client.newCall(request).execute().use { resp ->
                        val bodyText = resp.body?.string().orEmpty()
                        val reply = try {
                            val o = JSONObject(bodyText)
                            Pair(
                                o.optString("reply", "").trim(),
                                o.optBoolean("injected_live", false),
                            )
                        } catch (e: Exception) {
                            Pair("", false)
                        }
                        val (replyText, injected) = reply

                        if (injected) {
                            // Message went into a live session. The real
                            // answer arrives via that session's own hook
                            // notify — don't speak the "Delivered"
                            // confirmation as if it were a reply.
                            updateNotification("Delivered to live session")
                        } else if (replyText.isNotEmpty()) {
                            // One-shot reply: speak it back (hands-free) and
                            // persist it so MainActivity can show it.
                            updateNotification("Hermes: ${replyText.take(120)}")
                            // Record the reply in the shared history file
                            // (works even when MainActivity is paused/dead).
                            try {
                                ChatHistoryStore(applicationContext)
                                    .append(ChatMessage("hermes", replyText))
                            } catch (e: Exception) {
                                // history is best-effort
                            }
                            // If MainActivity is alive, tell it to refresh
                            // history + queue the reply for its audio player
                            // (which dedupes against its own WS replies).
                            sendBroadcast(
                                Intent(ACTION_REPLY_READY)
                                    .setPackage(packageName)
                                    .putExtra(EXTRA_REPLY_TEXT, replyText)
                            )
                            // Fallback for the truly-background case: speak
                            // here so the wake word is never one-way. The
                            // flag lets MainActivity skip its own TTS if it
                            // also received the broadcast (no double-speak).
                            speakFromService(replyText)
                        } else {
                            updateNotification("Message sent")
                        }
                    }
                } catch (e: Exception) {
                    updateNotification("Couldn't send — check server")
                }
            }.start()
        } catch (e: Exception) {
            updateNotification("Couldn't send — check server")
        }
    }

    // The reply is spoken by the SERVICE (MainActivity may be paused/dead
    // behind the overlay). MainActivity's ACTION_REPLY_READY handler only
    // refreshes history — it never re-speaks — so there's exactly one
    // voice for wake-word replies.
    private var replyTts: TextToSpeech? = null
    private var queuedReplyText: String? = null

    private fun speakFromService(text: String) {
        try {
            // Same routing as the app's AudioPlayer: mute silences all,
            // BT-only requires a headset, otherwise use the phone speaker.
            if (AppSettings.muteVoice(this)) return
            if (AppSettings.playOverBluetoothOnly(this) && !isBluetoothConnected()) return
            queuedReplyText = text
            if (replyTts == null) {
                replyTts = TextToSpeech(this) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        replyTts?.language = Locale.US
                        speakQueuedReply()
                    }
                }
            } else {
                speakQueuedReply()
            }
        } catch (e: Exception) {
            // best-effort
        }
    }

    private fun speakQueuedReply() {
        val text = queuedReplyText ?: return
        queuedReplyText = null
        try {
            replyTts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "wake_reply")
        } catch (e: Exception) {
            // best-effort
        }
    }

    /** A2DP output device connected (same check the app's AudioPlayer uses). */
    private fun isBluetoothConnected(): Boolean {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            for (device in audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                if (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) {
                    return true
                }
            }
        } catch (e: Exception) {
            // fall through to adapter check
        }
        return try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            adapter != null && adapter.isEnabled &&
                adapter.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothProfile.STATE_CONNECTED
        } catch (e: SecurityException) {
            false
        }
    }

    /** True when the final hypothesis is exactly the wake phrase. */
    private fun isWakeWordMatch(hypothesis: String?): Boolean {
        if (hypothesis.isNullOrBlank()) return false
        // Vosk final result is JSON: {"text": "..."} — parse the text field
        // and compare against the phrase (case-insensitive, trimmed).
        return try {
            val text = org.json.JSONObject(hypothesis).optString("text", "").trim()
            text.equals("hey hermes", ignoreCase = true)
        } catch (e: Exception) {
            // Not JSON (shouldn't happen for a final result) — fall back to
            // a plain substring check on the raw string.
            hypothesis.lowercase().contains("hey hermes")
        }
    }

    override fun onFinalResult(hypothesis: String?) {
        if (dictationMode) {
            stopVoskService()
            dictationMode = false
            dictationFromWakeWord = false
            ChimePlayer.playStop(this)
            startWakeWordNow()
        }
    }

    override fun onError(exception: Exception?) {
        if (dictationMode) {
            dictationMode = false
            dictationFromWakeWord = false
            ChimePlayer.playStop(this)
            startWakeWordNow()
        }
    }

    override fun onTimeout() {
        // Dictation outer cap reached with no final result.
        if (dictationMode) {
            stopVoskService()
            dictationMode = false
            dictationFromWakeWord = false
            ChimePlayer.playStop(this)
            startWakeWordNow()
        }
    }

    private fun wakeWordHeard() {
        stopVoskService()
        updateNotification("Wake word heard")
        // Chime immediately. Then surface the assistant:
        //  - if draw-over is granted, show the COMPOSE floating overlay
        //    (Google-Assistant style: no focus steal, stays over the
        //    user's app) with the mic ready;
        //  - otherwise launch the full Compose activity as fallback.
        ChimePlayer.playStart(this)
        if (overlay.canShow()) {
            overlay.show(
                onExpand = { expandFromOverlay() },
                onMic = { AppViewModelProvider.viewModel.beginListening() },
            )
        } else {
            launchMainActivity()
        }
    }

    /** Launch the assistant surface into listening mode. Prefers the
     *  Compose bottom-sheet (Phase 3); both surfaces share the app-scoped
     *  AssistantViewModel so state carries over either way. */
    private fun launchMainActivity() {
        val launch = Intent(this, AssistantComposeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            action = "com.example.hermesassistant.START_LISTENING"
        }
        try {
            startActivity(launch)
        } catch (e: Exception) {
            // e.g. background activity start blocked — a surface may be
            // alive in the background; also send the broadcast as fallback.
            sendBroadcast(
                Intent(ACTION_WAKE_WORD).setPackage(packageName)
            )
        }
    }

    // --- Compact overlay (over other apps) ---
    // The Compose AssistantOverlay replaces the legacy XML overlay panel
    // (overlay_panel.xml) which predates the redesign. The old window
    // plumbing was the right idea; the new card is a real Compose surface
    // bound to the app-scoped ViewModel (same orb/status/mic as the
    // sheet), so it stays visually consistent.

    private val overlay by lazy { AssistantOverlay(this) }

    private fun expandFromOverlay() {
        overlay.hide()
        launchMainActivity()
    }

    // --- Foreground notification ---
    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Hermes Assistant connection",
                NotificationManager.IMPORTANCE_LOW // silent, no badge/sound
            ).apply {
                description = "Keeps Hermes Assistant alive to receive notifications"
                setShowBadge(false)
            }
        )
        updateNotification("Listening for \"Hey Hermes\"")
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        val openIntent = Intent(this, AssistantComposeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            this,
            REQUEST_CODE,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Hermes Assistant is running")
            .setContentText(text)
            .setOngoing(true) // not dismissible — it's the app's lifeline
            .setContentIntent(pi)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        stopVoskService()
        overlay.hide()
        try { replyTts?.stop(); replyTts?.shutdown() } catch (e: Exception) {}
        replyTts = null
        setInstance(null)
        super.onDestroy()
    }
}
