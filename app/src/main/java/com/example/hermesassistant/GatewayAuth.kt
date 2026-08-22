package com.example.hermesassistant

import android.content.Context
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Honest, user-facing auth failure (bad credentials, ticket rejection...). */
class GatewayAuthException(message: String) : Exception(message)

/**
 * Username/password auth for the hermes serve gateway (dashboard basic_auth).
 *
 * Flow (mirrors the desktop app's remote mode):
 *   POST /auth/password-login  -> HMAC session cookies (access 12h / refresh 30d)
 *   POST /api/auth/ws-ticket   -> single-use 30s ticket for the /api/ws upgrade
 *
 * The session cookie is persisted in SharedPreferences so reconnects and cold
 * starts (notification replies) reuse it without re-login. On a 401 the cookie
 * is cleared and one fresh login is attempted.
 */
class GatewayAuth(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Stored session cookie header value, or null when not logged in. */
    fun sessionCookie(): String? =
        prefs.getString(KEY_COOKIE, null)?.takeIf { it.isNotEmpty() }

    fun clearSession() {
        prefs.edit().remove(KEY_COOKIE).apply()
    }

    /** Ensure a session cookie exists (login if needed). True when usable. */
    @Synchronized
    fun ensureLoggedIn(): Boolean {
        if (sessionCookie() != null) return true
        return login()
    }

    /**
     * Mint a fresh single-use WS ticket (TTL 30s — mint immediately before
     * every connect). Re-logs-in once on 401.
     */
    @Synchronized
    fun mintTicket(): String {
        if (!ensureLoggedIn()) {
            throw GatewayAuthException("Login failed — check gateway username/password in Settings")
        }
        val first = post("/api/auth/ws-ticket", JSONObject(), sessionCookie())
        first.body.optString("ticket").takeIf { it.isNotEmpty() }?.let { return it }
        if (first.status != 401) {
            throw GatewayAuthException("Gateway ticket rejected (HTTP ${first.status})")
        }
        clearSession()
        if (!login()) {
            throw GatewayAuthException("Login failed — check gateway username/password in Settings")
        }
        val second = post("/api/auth/ws-ticket", JSONObject(), sessionCookie())
        return second.body.optString("ticket").takeIf { it.isNotEmpty() }
            ?: throw GatewayAuthException("Gateway ticket rejected (HTTP ${second.status})")
    }

    private fun login(): Boolean {
        return try {
            val resp = post(
                "/auth/password-login",
                JSONObject()
                    .put("provider", "basic")
                    .put("username", ServerConfig.gatewayUser(context))
                    .put("password", ServerConfig.gatewayPass(context)),
                cookie = null,
            )
            if (resp.status == 200 && resp.body.optBoolean("ok", false)) {
                storeCookies(resp.setCookie)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    private class AuthResponse(
        val status: Int,
        val body: JSONObject,
        val setCookie: List<String>,
    )

    private fun post(path: String, jsonBody: JSONObject, cookie: String?): AuthResponse {
        val builder = Request.Builder()
            .url(ServerConfig.gatewayHttpBase(context) + path)
            .post(jsonBody.toString().toRequestBody(JSON_MEDIA_TYPE))
        if (cookie != null) builder.header("Cookie", cookie)
        client.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val body = try {
                JSONObject(text)
            } catch (e: Exception) {
                JSONObject()
            }
            return AuthResponse(resp.code, body, resp.headers("Set-Cookie"))
        }
    }

    private fun storeCookies(setCookie: List<String>) {
        val pairs = setCookie.mapNotNull { c ->
            c.substringBefore(";").takeIf { it.contains("=") }
        }
        if (pairs.isNotEmpty()) {
            prefs.edit().putString(KEY_COOKIE, pairs.joinToString("; ")).apply()
        }
    }

    companion object {
        private const val PREFS = "gateway_auth"
        private const val KEY_COOKIE = "session_cookie"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaTypeOrNull()
    }
}
