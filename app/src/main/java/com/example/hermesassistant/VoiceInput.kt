package com.example.hermesassistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Owns the two listening paths and the chime transitions between them:
 *
 *   idle -> startListening()   (Google STT, online)     start chime
 *   idle -> startOfflineDictation() (Vosk, offline)     start chime
 *   listening -> done (onResults/onError/cancel)        stop chime
 *
 * One [State] tracks what the recognizers are doing so callers never
 * double-start, and the button can cancel either path.
 */
class VoiceInput(private val context: Context) {

    enum class State { IDLE, STT, DICTATION }

    interface Listener {
        fun onTextCaptured(text: String)   // full phrase from either path
        fun onStateChanged(state: State)
        fun onError(message: String)
        fun onListening()                  // mic is now open
        fun onStoppedListening()           // mic released / phrase captured
        fun onThinking()                   // phrase sent, awaiting response
        fun onRmsLevel(rmsdB: Float)       // live mic amplitude while listening
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var state = State.IDLE
    private var listener: Listener? = null
    // A3: the stop chime must fire ONCE per listening session. Multiple
    // callbacks (onEndOfSpeech, then onResults) both signal "done"; the
    // flag collapses them into a single onStoppedListening notification.
    private var stoppedNotified = false

    fun attach(listener: Listener) {
        this.listener = listener
    }

    val isActive: Boolean get() = state != State.IDLE

    // ------------------------------------------------------------------
    // Entry points
    // ------------------------------------------------------------------

    /** Google STT (online path). Idempotent: no-op if already active. */
    fun startListening() {
        if (state != State.IDLE) return
        ensureRecognizer()
        state = State.STT
        stoppedNotified = false
        listener?.onListening()
        listener?.onStateChanged(state)

        // The AudioRecord release takes a moment to propagate through the
        // audio HAL; starting STT immediately can fail with ERROR_AUDIO.
        // Small settle delay is the standard fix for this handoff.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (state != State.STT) return@postDelayed // cancelled meanwhile
            try {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                    // Wait longer before finalizing: a pause mid-thought
                    // shouldn't send the text immediately. These are hints
                    // the Google recognizer generally honors (Samsung too).
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
                }
                speechRecognizer?.startListening(intent)
            } catch (e: Exception) {
                finishWithError("STT failed to start: ${e.message?.take(80)}")
            }
        }, 300)
    }

    /** Transition to IDLE with an error surfaced to the caller. */
    private fun finishWithError(message: String) {
        if (state != State.STT) return
        state = State.IDLE
        notifyStopped()
        listener?.onStateChanged(state)
        listener?.onError(message)
    }

    /** Vosk dictation (offline path). Idempotent. */
    fun startOfflineDictation() {
        if (state != State.IDLE) return
        state = State.DICTATION
        stoppedNotified = false
        listener?.onListening()
        listener?.onStateChanged(state)
        HermesForegroundService.startDictation()
    }

    /** Cancel whichever path is active (button tap = cancel). */
    fun cancel() {
        when (state) {
            State.STT -> {
                state = State.IDLE
                try {
                    speechRecognizer?.cancel()
                    speechRecognizer?.stopListening()
                } catch (e: Exception) {
                    // best-effort
                }
                notifyStopped()
                listener?.onStateChanged(state)
            }
            State.DICTATION -> {
                state = State.IDLE
                notifyStopped()
                listener?.onStateChanged(state)
            }
            State.IDLE -> {}
        }
    }

    /** Fire onStoppedListening exactly once per listening session. */
    private fun notifyStopped() {
        if (stoppedNotified) return
        stoppedNotified = true
        listener?.onStoppedListening()
    }

    // ------------------------------------------------------------------
    // Results (Google STT)
    // ------------------------------------------------------------------

    private fun ensureRecognizer() {
        if (speechRecognizer != null) return
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {
                listener?.onRmsLevel(rmsdB)
            }
            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                listener?.onThinking()
                notifyStopped()
            }

            override fun onError(error: Int) {
                if (state != State.STT) return
                state = State.IDLE
                notifyStopped()
                listener?.onStateChanged(state)
                listener?.onError(sttErrorText(error))
            }

            override fun onResults(results: Bundle?) {
                if (state != State.STT) return
                state = State.IDLE
                notifyStopped()
                listener?.onStateChanged(state)
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    listener?.onTextCaptured(matches[0])
                } else {
                    listener?.onError("Nothing recognized")
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    /** Real Android error text, not a blanket label (honest diagnostics). */
    private fun sttErrorText(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
        SpeechRecognizer.ERROR_CLIENT -> "Client-side error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission missing"
        SpeechRecognizer.ERROR_NETWORK -> "Network error (offline?)"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
        SpeechRecognizer.ERROR_SERVER -> "Recognition server error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard — timeout"
        else -> "Speech error ($error)"
    }

    // ------------------------------------------------------------------
    // External notification (Vosk dictation result arrives via broadcast)
    // ------------------------------------------------------------------

    /** MainActivity forwards the service's dictation result here. */
    fun onDictationResult(text: String) {
        state = State.IDLE
        notifyStopped()
        listener?.onStateChanged(state)
        if (text.isNotEmpty()) listener?.onTextCaptured(text)
    }

    fun shutdown() {
        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {}
        speechRecognizer = null
        state = State.IDLE
    }
}
