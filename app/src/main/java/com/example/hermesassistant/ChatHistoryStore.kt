package com.example.hermesassistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A single chat entry. role: "user" | "hermes" | "notify".
 * queued=true means the user message is sitting in the offline
 * queue, not yet delivered to the server.
 * sessionId/sessionTitle identify which Hermes session the message
 * belongs to ("" = the phone's default daily session). Used to select
 * the matching session chip when the message is tapped.
 */
data class ChatMessage(
    val role: String,
    val text: String,
    val ts: Long = System.currentTimeMillis(),
    val queued: Boolean = false,
    val sessionId: String = "",
    val sessionTitle: String = "",
    val injected: Boolean = false,
)

/**
 * Ring buffer of recent chat messages, persisted to
 * filesDir/chat_history.json so it survives app restarts.
 * Also owns the offline queue (filesDir/pending_queue.json).
 */
class ChatHistoryStore(context: Context) {

    private val historyFile = File(context.filesDir, "chat_history.json")
    private val queueFile = File(context.filesDir, "pending_queue.json")

    private val maxMessages = 50

    @Volatile
    var messages: List<ChatMessage> = emptyList()
        private set

    @Volatile
    var queue: List<ChatMessage> = emptyList()
        private set

    init {
        messages = loadJson(historyFile)
        queue = loadJson(queueFile)
    }

    fun append(message: ChatMessage) {
        val next = (messages + message).takeLast(maxMessages)
        messages = next
        saveJson(historyFile, next)
    }

    fun enqueue(text: String) {
        val entry = ChatMessage("user", text, queued = true)
        queue = queue + entry
        append(entry) // also show it in history immediately
        saveJson(queueFile, queue)
    }

    fun popQueued(): ChatMessage? {
        if (queue.isEmpty()) return null
        val head = queue.first()
        queue = queue.drop(1)
        saveJson(queueFile, queue)
        return head
    }

    fun requeue(entry: ChatMessage) {
        queue = listOf(entry.copy(queued = true)) + queue
        saveJson(queueFile, queue)
    }

    fun clearHistory() {
        messages = emptyList()
        saveJson(historyFile, messages)
    }

    /**
     * Re-read history from disk. Needed when another component in the same
     * process (e.g. NotificationReplyReceiver handling an inline reply)
     * appended to the shared chat_history.json while the activity's
     * in-memory list was stale.
     */
    fun reload() {
        messages = loadJson(historyFile)
    }

    fun markQueuedDelivered(entry: ChatMessage) {
        // Replace the queued marker in history with the delivered form
        messages = messages.map {
            if (it.ts == entry.ts && it.queued) it.copy(queued = false) else it
        }
        saveJson(historyFile, messages)
    }

    /**
     * Mark the most recent user bubble for this session as delivered to a
     * live session (relay returned injected_live=true). The check mark in
     * the bubble is driven by the ChatMessage.injected flag.
     */
    fun markInjected(sessionId: String) {
        var changed = false
        messages = messages.mapIndexed { i, m ->
            if (!changed && m.role == "user" && m.sessionId == sessionId && !m.injected) {
                changed = true
                m.copy(injected = true)
            } else m
        }
        if (changed) saveJson(historyFile, messages)
    }

    private fun loadJson(file: File): List<ChatMessage> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                ChatMessage(
                    role = o.optString("role", "user"),
                    text = o.optString("text", ""),
                    ts = o.optLong("ts", System.currentTimeMillis()),
                    queued = o.optBoolean("queued", false),
                    sessionId = o.optString("sessionId", ""),
                    sessionTitle = o.optString("sessionTitle", ""),
                    injected = o.optBoolean("injected", false),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveJson(file: File, list: List<ChatMessage>) {
        try {
            val arr = JSONArray()
            list.forEach { m ->
                arr.put(JSONObject().apply {
                    put("role", m.role)
                    put("text", m.text)
                    put("ts", m.ts)
                    put("queued", m.queued)
                    put("sessionId", m.sessionId)
                    put("sessionTitle", m.sessionTitle)
                    put("injected", m.injected)
                })
            }
            file.writeText(arr.toString())
        } catch (e: Exception) {
            // Best-effort persistence
        }
    }
}
