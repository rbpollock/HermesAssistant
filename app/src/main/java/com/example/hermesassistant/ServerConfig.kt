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

    // Gateway (hermes serve / tui_gateway) settings. Shares the host with
    // the relay; only the port and credentials differ.
    private const val KEY_GATEWAY_MODE = "gateway_mode"
    private const val KEY_GATEWAY_PORT = "gateway_port"
    private const val KEY_GATEWAY_USER = "gateway_user"
    private const val KEY_GATEWAY_PASS = "gateway_pass"

    const val DEFAULT_GATEWAY_PORT = "9119"
    const val DEFAULT_GATEWAY_USER = "robbie"
    // Bundled default so the client connects with zero setup (the server's
    // auth gate always requires a provider on non-loopback binds; there is
    // no unauthenticated path in hermes v0.20.4). Overridable in Settings.
    const val DEFAULT_GATEWAY_PASS = "KYYRHuaRikkCqfb7"

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

    // ------------------------------------------------------------------
    // Gateway (hermes serve :9119) — the JSON-RPC transport
    // ------------------------------------------------------------------

    /** True = talk to the tui_gateway (JSON-RPC); false = legacy relay. */
    fun gatewayMode(context: Context): Boolean =
        prefs(context).getBoolean(KEY_GATEWAY_MODE, true)

    fun setGatewayMode(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_GATEWAY_MODE, value).apply()
    }

    fun gatewayPort(context: Context): String =
        prefs(context).getString(KEY_GATEWAY_PORT, DEFAULT_GATEWAY_PORT) ?: DEFAULT_GATEWAY_PORT

    fun gatewayUser(context: Context): String =
        prefs(context).getString(KEY_GATEWAY_USER, DEFAULT_GATEWAY_USER) ?: DEFAULT_GATEWAY_USER

    fun gatewayPass(context: Context): String =
        prefs(context).getString(KEY_GATEWAY_PASS, DEFAULT_GATEWAY_PASS)
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_GATEWAY_PASS

    fun saveGateway(context: Context, port: String, user: String, pass: String) {
        prefs(context).edit()
            .putString(KEY_GATEWAY_PORT, port.trim())
            .putString(KEY_GATEWAY_USER, user.trim())
            .putString(KEY_GATEWAY_PASS, pass.trim())
            .apply()
    }

    /** Gateway HTTP origin, e.g. "http://100.123.127.108:9119" */
    fun gatewayHttpBase(context: Context): String {
        val h = host(context).trim().trimEnd('/')
        val p = gatewayPort(context).trim().trimStart(':')
        return "http://$h:$p"
    }

    /** Gateway WebSocket origin, e.g. "ws://100.123.127.108:9119" */
    fun gatewayWsBase(context: Context): String {
        val h = host(context).trim().trimEnd('/')
        val p = gatewayPort(context).trim().trimStart(':')
        return "ws://$h:$p"
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
