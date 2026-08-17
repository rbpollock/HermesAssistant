package com.example.hermesassistant

import android.content.Context

/**
 * User-facing audio preferences, persisted in SharedPreferences.
 *
 * - playOverBluetoothOnly (default ON): when ON, responses and spoken
 *   alerts only play while a Bluetooth A2DP device is connected; when
 *   OFF they play over the phone speaker instead.
 * - muteVoice (default OFF): a general mute — notifications still post,
 *   but Hermes never speaks aloud (no response audio, no TTS alerts).
 */
object AppSettings {

    private const val PREFS = "app_settings"
    private const val KEY_BT_ONLY = "play_bt_only"
    private const val KEY_MUTE_VOICE = "mute_voice"

    fun playOverBluetoothOnly(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BT_ONLY, true)

    fun setPlayOverBluetoothOnly(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_BT_ONLY, value).apply()
    }

    fun muteVoice(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MUTE_VOICE, false)

    fun setMuteVoice(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_MUTE_VOICE, value).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
