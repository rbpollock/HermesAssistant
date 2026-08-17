package com.example.hermesassistant

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import java.io.File
import java.util.ArrayDeque
import java.util.Locale

/**
 * Unified FIFO audio playback: server response MP3s (via MediaPlayer)
 * and notify alerts (via TextToSpeech) feed one queue and play strictly
 * one at a time, so an alert never talks over response audio.
 *
 * Also owns the Bluetooth-gated autoplay decision and the "don't re-read
 * the response as a notify alert" (echo/chorus) tracking.
 */
class AudioPlayer(private val context: Context) {

    interface Listener {
        fun onQueueEmpty()      // response finished — caller may auto-listen
        fun onSpeakingStarted()
        fun onSpeakingStopped()
        fun onStatus(message: String)
    }

    private data class PlaybackItem(val audioFile: File?, val spokenText: String?)

    private val playbackQueue = ArrayDeque<PlaybackItem>()
    private var isSpeaking = false
    private var mediaPlayer: MediaPlayer? = null
    private var tts: TextToSpeech? = null

    // Guards onPlaybackItemDone against double-firing (some TTS engines
    // fire both onError and onDone for one utterance; a stale callback
    // must not skip the next queued item).
    private var playbackItemInFlight = false

    // The last response text played as server audio. A notify alert with
    // the SAME text ("Hermes finished" for the same response) would just
    // re-read it — skip the duplicate TTS to avoid the echo/chorus.
    private var lastSpokenResponseText: String? = null

    private val playbackWatchdog = Handler(Looper.getMainLooper())
    private var playbackWatchdogRunnable: Runnable? = null
    private val PLAYBACK_WATCHDOG_MS = 20000L

    private var listener: Listener? = null

    fun attach(listener: Listener) {
        this.listener = listener
    }

    // ------------------------------------------------------------------
    // TTS
    // ------------------------------------------------------------------

    fun initTts() {
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
            }
        }
        registerTtsListener()
    }

    private fun registerTtsListener() {
        tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                main { onPlaybackItemDone() }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                main { onPlaybackItemDone() }
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
                main { onPlaybackItemDone() }
            }
        })
    }

    // ------------------------------------------------------------------
    // Queue
    // ------------------------------------------------------------------

    fun rememberSpokenResponse(text: String) {
        if (text.isNotEmpty()) lastSpokenResponseText = text
    }

    /** True when a notify message is the same text the audio just spoke. */
    fun isResponseEcho(message: String): Boolean =
        message.isNotEmpty() && message == lastSpokenResponseText

    /** Queue a server MP3 for playback. */
    fun playAudio(file: File) {
        if (!isBluetoothConnected()) {
            listener?.onStatus("Response ready — connect Bluetooth to hear it, or tap to play")
            return
        }
        enqueue(PlaybackItem(audioFile = file, spokenText = null))
    }

    /** Queue a TTS alert. Caller decides Bluetooth gating + echo skip. */
    fun speakAlert(title: String, message: String) {
        if (!isBluetoothConnected()) return
        enqueue(PlaybackItem(audioFile = null, spokenText = "$title. $message"))
    }

    fun playPending(file: File?) {
        file?.let { enqueue(PlaybackItem(audioFile = it, spokenText = null)) }
    }

    /** True while an item is mid-playback (guards auto-listen re-entry). */
    fun playbackInFlight(): Boolean = isSpeaking

    private fun enqueue(item: PlaybackItem) {
        playbackQueue.addLast(item)
        if (!isSpeaking) startNextPlayback()
    }

    private fun startNextPlayback() {
        if (playbackQueue.isEmpty()) {
            isSpeaking = false
            disarmPlaybackWatchdog()
            listener?.onQueueEmpty()
            return
        }
        val item = playbackQueue.removeFirst()
        isSpeaking = true
        listener?.onSpeakingStarted()
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
                        setOnCompletionListener { main { onPlaybackItemDone() } }
                        setOnErrorListener { _, _, _ -> main { onPlaybackItemDone() }; true }
                        prepare()
                        start()
                    }
                } catch (e: Exception) {
                    // Bad/empty file — skip it, keep the queue moving
                    main { onPlaybackItemDone() }
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
        listener?.onSpeakingStopped()
        startNextPlayback()
    }

    private fun armPlaybackWatchdog() {
        playbackWatchdogRunnable?.let { playbackWatchdog.removeCallbacks(it) }
        val runnable = Runnable { onPlaybackItemDone() }
        playbackWatchdogRunnable = runnable
        playbackWatchdog.postDelayed(runnable, PLAYBACK_WATCHDOG_MS)
    }

    private fun disarmPlaybackWatchdog() {
        playbackWatchdogRunnable?.let { playbackWatchdog.removeCallbacks(it) }
        playbackWatchdogRunnable = null
    }

    // ------------------------------------------------------------------
    // Bluetooth
    // ------------------------------------------------------------------

    fun isBluetoothConnected(): Boolean {
        // Check A2DP output devices (no permission needed for output device list)
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
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
    // Lifecycle
    // ------------------------------------------------------------------

    fun shutdown() {
        disarmPlaybackWatchdog()
        playbackQueue.clear()
        isSpeaking = false
        try {
            mediaPlayer?.release()
        } catch (e: Exception) {}
        mediaPlayer = null
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {}
        tts = null
    }

    private fun main(r: () -> Unit) {
        Handler(Looper.getMainLooper()).post(r)
    }
}
