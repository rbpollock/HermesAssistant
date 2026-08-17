package com.example.hermesassistant

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/**
 * Plays the short UI chimes that mark the start/end of listening.
 *
 * A plain helper object (no service, no component) using SoundPool for
 * low-latency playback of the two bundled WAVs (res/raw/chime_start.wav
 * and chime_stop.wav). Call [init] once from a long-lived context (the
 * foreground service or MainActivity) so the sounds are preloaded.
 */
object ChimePlayer {

    private var pool: SoundPool? = null
    private var startId = 0
    private var stopId = 0

    fun init(context: Context) {
        if (pool != null) return
        try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            pool = SoundPool.Builder()
                .setMaxStreams(2)
                .setAudioAttributes(attrs)
                .build()
            startId = pool?.load(context, R.raw.chime_start, 1) ?: 0
            stopId = pool?.load(context, R.raw.chime_stop, 1) ?: 0
        } catch (e: Exception) {
            // Chimes are best-effort; never let a missing sound break flow.
            pool = null
        }
    }

    fun playStart() {
        pool?.play(startId, 1f, 1f, 1, 0, 1f)
    }

    fun playStop() {
        pool?.play(stopId, 1f, 1f, 1, 0, 1f)
    }

    fun release() {
        pool?.release()
        pool = null
    }
}
