package com.example.hermesassistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

import okio.ByteString
import okio.ByteString.Companion.toByteString

import java.util.concurrent.TimeUnit

import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var statusText: TextView
    private lateinit var speakButton: Button
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(64, 64, 64, 64)
        }
        
        statusText = TextView(this).apply {
            text = "Ready"
            textSize = 24f
            setPadding(0, 0, 0, 64)
        }
        
        speakButton = Button(this).apply {
            text = "TAP TO SPEAK TO HERMES"
            textSize = 20f
        }

        layout.addView(statusText)
        layout.addView(speakButton)
        setContentView(layout)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }

        setupSpeechRecognizer()
        connectWebSocket()

        speakButton.setOnClickListener { startListening() }

        // Auto-start listening if invoked via the OS Assistant hardware button
        if (intent?.action == Intent.ACTION_ASSIST) {
            startListening()
        }
    }

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
                    statusText.text = "Connected to Server" 
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
                runOnUiThread { statusText.text = "WS Error: ${t.message}" }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runOnUiThread {
                    try {
                        val json = JSONObject(text)
                        val type = json.optString("type")
                        if (type == "text") {
                            statusText.text = "Hermes: ${json.optString("message")}"
                        } else if (type == "status") {
                            statusText.text = json.optString("message")
                        } else if (type == "audio_end") {
                            playAudioStream()
                        }
                    } catch (e: Exception) {
                        statusText.text = "Error parsing: ${e.message}"
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Receive streaming raw MP3 byte chunks
                if (audioOutputStream == null) {
                    audioTempFile = File(cacheDir, "stream_${System.currentTimeMillis()}.mp3")
                    audioOutputStream = FileOutputStream(audioTempFile)
                }
                audioOutputStream?.write(bytes.toByteArray())
            }
        })
    }

    private fun playAudioStream() {
        audioOutputStream?.close()
        audioOutputStream = null
        
        audioTempFile?.let { file ->
            runOnUiThread {
                mediaPlayer?.release()
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    prepare()
                    start()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Auto-start listening if invoked while the app is already open in the background
        if (intent.action == Intent.ACTION_ASSIST) {
            startListening()
        }
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
        }
        speechRecognizer.startListening(intent)
        statusText.text = "Listening..."
    }

    private fun setupSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                statusText.text = "Thinking..."
            }
            override fun onError(error: Int) {
                statusText.text = "Error listening. Try again."
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val userText = matches[0]
                    statusText.text = "You: $userText\n\nSending to Hermes..."
                    
                    if (isConnected && webSocket?.send(userText) == true) {
                        // Sent successfully
                    } else {
                        isConnected = false
                        statusText.text = "Disconnected. Reconnecting..."
                        pendingMessage = userText
                        connectWebSocket()
                    }
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
                runOnUiThread { statusText.text = "Connection Error: ${e.message}" }
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    runOnUiThread { statusText.text = "Error from server: ${response.code}" }
                    return
                }

                val responseText = response.header("X-Response-Text", "Audio received")
                runOnUiThread { statusText.text = "Hermes: $responseText" }

                val tempFile = File(cacheDir, "hermes_reply.mp3")
                val sink = FileOutputStream(tempFile)
                sink.use { it.write(response.body!!.bytes()) }

                val mediaPlayer = MediaPlayer()
                mediaPlayer.setDataSource(tempFile.absolutePath)
                mediaPlayer.prepare()
                mediaPlayer.start()
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer.destroy()
    }
}