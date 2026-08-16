package com.example.hermesassistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

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
            instance?.hideOverlay()
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
    private var micPermissionGranted = false

    // "Over other apps" compact panel (bottom third). Shown when the
    // wake word fires while another app is foreground; tapping it
    // expands to the full MainActivity.
    private var overlayView: android.view.View? = null
    private var overlayStatusText: android.widget.TextView? = null
    private var overlaySubText: android.widget.TextView? = null

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
            updateOverlayStatus("Listening for \"Hey Hermes\"", "Say \"Hey Hermes\" or tap to open")
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
            updateNotification("Dictating (offline)")
            updateOverlayStatus("Dictating (offline)", "Speak — your words will be queued")
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
            if (text.isNotEmpty()) {
                val i = Intent(ACTION_DICTATION_RESULT)
                    .setPackage(packageName)
                    .putExtra(EXTRA_DICTATION_TEXT, text)
                sendBroadcast(i)
            }
            // Back to wake word
            dictationMode = false
            startWakeWordNow()
            return
        }
        if (isWakeWordMatch(hypothesis)) {
            wakeWordHeard()
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
            startWakeWordNow()
        }
    }

    override fun onError(exception: Exception?) {
        if (dictationMode) {
            dictationMode = false
            startWakeWordNow()
        }
    }

    override fun onTimeout() {
        // Dictation outer cap reached with no final result.
        if (dictationMode) {
            stopVoskService()
            dictationMode = false
            startWakeWordNow()
        }
    }

    private fun wakeWordHeard() {
        stopVoskService()
        updateNotification("Wake word heard")
        // Prefer the compact overlay (bottom third over other apps) when we
        // have draw-over permission; tapping it expands to the full app.
        // Fall back to launching the activity directly when the permission
        // hasn't been granted.
        if (Settings.canDrawOverlays(this)) {
            showOverlay()
        } else {
            launchMainActivity()
        }
    }

    /** Launch the full MainActivity into listening mode. */
    private fun launchMainActivity() {
        val launch = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            action = "com.example.hermesassistant.START_LISTENING"
        }
        try {
            startActivity(launch)
        } catch (e: Exception) {
            // e.g. background activity start blocked — MainActivity may be
            // alive in the background; also send the broadcast as fallback.
            sendBroadcast(
                Intent(ACTION_WAKE_WORD).setPackage(packageName)
            )
        }
    }

    // --- Compact overlay (bottom third, over other apps) ---

    private fun showOverlay() {
        hideOverlay()
        try {
            val wm = getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
            val inflater = getSystemService(android.content.Context.LAYOUT_INFLATER_SERVICE) as android.view.LayoutInflater
            val view = inflater.inflate(R.layout.overlay_panel, null)
            overlayView = view
            overlayStatusText = view.findViewById(R.id.overlayStatusText)
            overlaySubText = view.findViewById(R.id.overlaySubText)

            val expand = view.findViewById<android.view.View>(R.id.overlayExpandBar)
            expand.setOnClickListener { expandFromOverlay() }
            view.findViewById<android.view.View>(R.id.overlaySpeakButton).setOnClickListener {
                expandFromOverlay()
            }

            // Bottom third of the screen
            val dm = resources.displayMetrics
            val height = (dm.heightPixels / 3).coerceAtLeast(dp(220))
            val params = android.view.WindowManager.LayoutParams(
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                height,
                android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT
            ).apply {
                gravity = android.view.Gravity.BOTTOM
            }
            wm.addView(view, params)
            updateOverlayStatus("Wake word heard", "Tap to open and speak")
        } catch (e: Exception) {
            overlayView = null
            // Overlay failed — just launch the activity instead
            launchMainActivity()
        }
    }

    private fun hideOverlay() {
        val view = overlayView ?: return
        overlayView = null
        overlayStatusText = null
        overlaySubText = null
        try {
            val wm = getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
            wm.removeView(view)
        } catch (e: Exception) {
            // Already removed
        }
    }

    private fun expandFromOverlay() {
        hideOverlay()
        launchMainActivity()
    }

    private fun updateOverlayStatus(status: String, sub: String) {
        overlayStatusText?.text = status
        overlaySubText?.text = sub
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

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
        val openIntent = Intent(this, MainActivity::class.java).apply {
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
        hideOverlay()
        setInstance(null)
        super.onDestroy()
    }
}
