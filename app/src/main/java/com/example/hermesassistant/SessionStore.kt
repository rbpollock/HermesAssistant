package com.example.hermesassistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A session the user has interacted with. Comes from notify events
 * (any host's session that pinged the phone) plus the phone's own
 * daily session. Persisted so the chip list survives restarts.
 */
data class KnownSession(
    val id: String,
    val title: String,
    val host: String = "",
    val lastSeen: Long = System.currentTimeMillis(),
)

/**
 * Persisted list of known sessions (filesDir/sessions.json), kept in
 * most-recently-seen order, bounded to avoid unbounded growth.
 */
class SessionStore(context: Context) {

    private val file = File(context.filesDir, "sessions.json")
    private val maxSessions = 30

    @Volatile
    var sessions: List<KnownSession> = emptyList()
        private set

    init {
        sessions = loadJson()
    }

    /** Insert or bump a session to the front. */
    fun upsert(session: KnownSession) {
        val now = System.currentTimeMillis()
        val cleaned = sessions.filterNot { it.id == session.id }
        val entry = session.copy(lastSeen = now)
        sessions = (listOf(entry) + cleaned).take(maxSessions)
        saveJson(sessions)
    }

    fun clear() {
        sessions = emptyList()
        saveJson(sessions)
    }

    private fun loadJson(): List<KnownSession> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                KnownSession(
                    id = o.optString("id", ""),
                    title = o.optString("title", ""),
                    host = o.optString("host", ""),
                    lastSeen = o.optLong("lastSeen", System.currentTimeMillis()),
                )
            }.filter { it.id.isNotEmpty() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveJson(list: List<KnownSession>) {
        try {
            val arr = JSONArray()
            list.forEach { s ->
                arr.put(JSONObject().apply {
                    put("id", s.id)
                    put("title", s.title)
                    put("host", s.host)
                    put("lastSeen", s.lastSeen)
                })
            }
            file.writeText(arr.toString())
        } catch (e: Exception) {
            // Best-effort persistence
        }
    }
}
