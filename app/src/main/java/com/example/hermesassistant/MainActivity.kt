package com.example.hermesassistant

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.widget.Button
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
import okio.ByteString.Companion.toByteString

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
    private lateinit var notificationManager: NotificationManager
    private var tts: TextToSpeech? = null

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

    // For streaming audio chunks
    private var audioTempFile: File? = null
    private var audioOutputStream: FileOutputStream? = null

    private var voskModel: Model? = null
    private var voskService: SpeechService? = null

    // Guards against duplicate auto-listen triggers
    private var autoListenScheduled = false

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
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        createNotificationChannel()
        initTts()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        requestNotificationPermissionIfNeeded()

        setupSpeechRecognizer()
        connectWebSocket()
        initVosk()

        speakButton.setOnClickListener {
            // If a response is waiting (no Bluetooth autoplay happened), tap plays it
            if (mediaPlayer == null && audioTempFile != null && !isSpeaking) {
                playPendingAudio()
            } else {
                startListening()
            }
        }

        // Auto-start listening if invoked via the OS Assistant hardware button
        // or via our custom action from the VoiceInteractionSession
        if (intent?.action == Intent.ACTION_ASSIST || intent?.action == "com.example.hermesassistant.START_LISTENING") {
            startListening()
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
    // Vosk wake word
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

    override fun onPartialResult(hypothesis: String?) {
        if (hypothesis?.contains("hey hermes") == true || hypothesis?.contains("hermes") == true) {
            triggerAssistantFromWakeWord()
        }
    }

    override fun onResult(hypothesis: String?) {
        if (hypothesis?.contains("hey hermes") == true || hypothesis?.contains("hermes") == true) {
            triggerAssistantFromWakeWord()
        }
    }

    override fun onFinalResult(hypothesis: String?) {}
    override fun onError(exception: Exception?) {}
    override fun onTimeout() {}

    private fun triggerAssistantFromWakeWord() {
        // Pause Vosk so Android's main recognizer can take the microphone
        voskService?.stop()
        voskService = null

        runOnUiThread {
            speakButton.text = "TAP TO SPEAK TO HERMES"
            startListening()
        }
    }

    // ------------------------------------------------------------------
    // WebSocket connection
    // ------------------------------------------------------------------

    private fun connectWebSocket() {
        if (isConnected) return

        webSocket?.cancel()

        val request = Request.Builder()
            .url("ws://100.123.127.108:8000/chat/stream")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                runOnUiThread {
                    setStatus("Connected to server", StatusRingView.State.CONNECTED)
                    pendingMessage?.let {
                        webSocket.send(it)
                        pendingMessage = null
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                runOnUiThread { setStatus("WS Error: ${t.message}", StatusRingView.State.IDLE) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runOnUiThread {
                    try {
                        val json = JSONObject(text)
                        val type = json.optString("type")
                        when (type) {
                            "text" -> {
                                setStatus("Hermes: ${json.optString("message")}", StatusRingView.State.CONNECTED)
                            }
                            "status" -> {
                                setStatus(json.optString("message"), StatusRingView.State.THINKING)
                            }
                            "audio_end" -> {
                                playAudioStream()
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

    // ------------------------------------------------------------------
    // Notify events from ANY Hermes session (shell hooks -> server relay)
    // ------------------------------------------------------------------

    private fun handleNotify(json: JSONObject) {
        val kind = json.optString("kind", "response")
        val title = json.optString("title", "Hermes")
        val message = json.optString("message", "")
        val host = json.optString("host", "")

        setStatus("$title\n$message", StatusRingView.State.CONNECTED)

        val urgent = kind == "question" || kind == "approval"
        showSystemNotification(title, message, host, urgent)

        // Speak the alert aloud ONLY when a Bluetooth device is connected
        if (isBluetoothConnected()) {
            tts?.speak("$title. $message", TextToSpeech.QUEUE_FLUSH, null, "notify")
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
            if (isSpeaking || voskService != null) return@postDelayed
            startListening()
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
    // Speech recognition
    // ------------------------------------------------------------------

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Auto-start listening if invoked while the app is already open in the background
        if (intent.action == Intent.ACTION_ASSIST || intent.action == "com.example.hermesassistant.START_LISTENING") {
            startListening()
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
                setStatus("Error listening. Try again.", StatusRingView.State.IDLE)
                startVoskWakeWord()
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val userText = matches[0]
                    setStatus("You: $userText", StatusRingView.State.THINKING)

                    if (isConnected && webSocket?.send(userText) == true) {
                        // Sent successfully
                    } else {
                        isConnected = false
                        setStatus("Disconnected. Reconnecting...", StatusRingView.State.THINKING)
                        pendingMessage = userText
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

                val responseText = response.header("X-Response-Text", "Audio received")
                runOnUiThread { setStatus("Hermes: $responseText", StatusRingView.State.CONNECTED) }

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

    private fun setStatus(text: String, state: StatusRingView.State) {
        statusText.text = text
        statusRing.state = state
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer.destroy()
        tts?.stop()
        tts?.shutdown()
        mediaPlayer?.release()
        webSocket?.cancel()
    }
}
