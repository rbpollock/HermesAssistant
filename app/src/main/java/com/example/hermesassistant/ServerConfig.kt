package com.example.hermesassistant

import android.content.Context

/**
 * Server connection settings (home server ip|port or hostname|port).
 * Stored in SharedPreferences so the user can point the app at any
 * Hermes relay server without rebuilding.
 *
 * Default: the Tailscale relay on irl-server-01.
 */
object ServerConfig {

    private const val PREFS = "server_config"
    private const val KEY_HOST = "server_host"
    private const val KEY_PORT = "server_port"

    const val DEFAULT_HOST = "100.123.127.108"
    const val DEFAULT_PORT = "8000"

    fun host(context: Context): String =
        prefs(context).getString(KEY_HOST, DEFAULT_HOST) ?: DEFAULT_HOST

    fun port(context: Context): String =
        prefs(context).getString(KEY_PORT, DEFAULT_PORT) ?: DEFAULT_PORT

    fun save(context: Context, host: String, port: String) {
        prefs(context).edit()
            .putString(KEY_HOST, host.trim())
            .putString(KEY_PORT, port.trim())
            .apply()
    }

    /** Base origin, e.g. "http://100.123.127.108:8000" */
    fun httpBase(context: Context): String {
        val h = host(context).trim().trimEnd('/')
        val p = port(context).trim().trimStart(':')
        return "http://$h:$p"
    }

    /** WebSocket origin, e.g. "ws://100.123.127.108:8000" */
    fun wsBase(context: Context): String {
        val h = host(context).trim().trimEnd('/')
        val p = port(context).trim().trimStart(':')
        return "ws://$h:$p"
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
