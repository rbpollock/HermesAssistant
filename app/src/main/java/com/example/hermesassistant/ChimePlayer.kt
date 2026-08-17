package com.example.hermesassistant

import android.content.Context
import android.media.MediaPlayer

/**
 * Plays the short UI chimes that mark the start/end of listening.
 *
 * A plain helper object (no service, no component). Uses MediaPlayer
 * instead of SoundPool because MediaPlayer.create() loads the asset
 * SYNCHRONOUSLY — SoundPool.load() is async, so a chime played right
 * after init() could fire before the sample was ready (silent). Media
 * also plays on the same music stream as the TTS responses, so it's
 * audible wherever the assistant's voice is.
 */
object ChimePlayer {

    fun playStart(context: Context) {
        play(context, R.raw.chime_start)
    }

    fun playStop(context: Context) {
        play(context, R.raw.chime_stop)
    }

    private fun play(context: Context, resId: Int) {
        try {
            // create() loads + prepares synchronously; returns null on
            // failure instead of throwing. One-shot: release on completion.
            val player = MediaPlayer.create(context, resId) ?: return
            player.setOnCompletionListener { it.release() }
            player.setOnErrorListener { mp, _, _ -> mp.release(); true }
            player.start()
        } catch (e: Exception) {
            // Chimes are best-effort; never let a missing sound break flow.
        }
    }
}
