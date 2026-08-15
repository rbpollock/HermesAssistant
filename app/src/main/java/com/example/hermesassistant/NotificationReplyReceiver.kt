package com.example.hermesassistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Handles the "Reply" action on Hermes notifications.
 *
 * The typed text is extracted from the RemoteInput and sent to the
 * server's HTTP /chat/message endpoint (NOT the WebSocket — the app
 * process may not be running when the user replies from the shade).
 * The reply comes back as a follow-up notification.
 */
class NotificationReplyReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_SESSION_ID = "reply_session_id"
        const val EXTRA_SESSION_TITLE = "reply_session_title"
        const val EXTRA_NOTIFY_TITLE = "reply_notify_title"
        const val KEY_TEXT_REPLY = "reply_text"
        private const val REPLY_CHANNEL = "hermes_replies"
        // Broadcast to the (possibly alive) MainActivity so it reloads its
        // history view after an inline reply appends to chat_history.json.
        const val ACTION_HISTORY_UPDATED = "com.example.hermesassistant.HISTORY_UPDATED"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        val sessionTitle = intent.getStringExtra(EXTRA_SESSION_TITLE).orEmpty()
        val notifyTitle = intent.getStringExtra(EXTRA_NOTIFY_TITLE).orEmpty()

        val text = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(KEY_TEXT_REPLY)
            ?.toString()
            ?.trim()
            .orEmpty()

        if (text.isEmpty()) {
            notifyReply(context, "Reply not sent", "Empty message", sessionId, sessionTitle)
            return
        }

        // Record the user's reply in the shared chat history immediately,
        // tagged with the session it was sent to. MainActivity reads the
        // same chat_history.json, so the message appears in the app even
        // if the process was backgrounded.
        appendToHistory(context, "user", text, sessionId, sessionTitle)
        broadcastHistoryUpdated(context)

        // HTTP call must happen off the main thread
        val pendingResult = goAsync()
        Thread {
            try {
                val body = JSONObject()
                    .put("message", text)
                    .put("session_id", sessionId)
                    .toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

                val request = Request.Builder()
                    .url("${ServerConfig.httpBase(context)}/chat/message")
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    val respBody = response.body?.string().orEmpty()
                    val json = JSONObject(respBody)
                    if (response.isSuccessful && json.optBoolean("ok", false)) {
                        val reply = json.optString("reply", "")
                        // Also record Hermes' answer in the shared history.
                        if (reply.isNotEmpty()) {
                            appendToHistory(context, "hermes", reply, sessionId, sessionTitle)
                        }
                        broadcastHistoryUpdated(context)
                        notifyReply(context, "Hermes replied", reply, sessionId, sessionTitle)
                    } else {
                        notifyReply(context, "Reply not sent", json.optString("error", "server error"), sessionId, sessionTitle)
                    }
                }
            } catch (e: Exception) {
                notifyReply(context, "Reply not sent", "Network error: ${e.message}", sessionId, sessionTitle)
            } finally {
                pendingResult.finish()
            }
        }.start()
    }

    /** Append a message to the shared chat_history.json (best-effort). */
    private fun appendToHistory(context: Context, role: String, text: String, sessionId: String, sessionTitle: String) {
        try {
            ChatHistoryStore(context).append(
                ChatMessage(
                    role = role,
                    text = text,
                    sessionId = sessionId,
                    sessionTitle = sessionTitle,
                )
            )
        } catch (e: Exception) {
            // History is best-effort — never let it break the reply flow.
        }
    }

    private fun broadcastHistoryUpdated(context: Context) {
        try {
            context.sendBroadcast(Intent(ACTION_HISTORY_UPDATED).setPackage(context.packageName))
        } catch (e: Exception) {
            // No listeners — fine.
        }
    }

    private fun notifyReply(context: Context, title: String, message: String, sessionId: String, sessionTitle: String = "") {
        ensureChannel(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Non-negative IDs only (negative IDs get dropped by some OEMs).
        // Shift into 200+ so reply notifications never collide with the
        // foreground service (ID 1) or the session notify (100+).
        val notifId = (sessionId.hashCode() and 0x7fffffff) % 1000000 + 200

        // Tapping the follow-up opens the app at the same session chip
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("target_session_id", sessionId)
            putExtra("target_session_title", title)
        }
        val pi = PendingIntent.getActivity(
            context,
            notifId + 200,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Follow-up "Reply" action: keep the conversation going with the
        // SAME session, straight from the shade. FLAG_MUTABLE is required
        // for actions with RemoteInput on Android 12+.
        val followUpReplyIntent = Intent(context, NotificationReplyReceiver::class.java).apply {
            putExtra(EXTRA_SESSION_ID, sessionId)
            putExtra(EXTRA_SESSION_TITLE, sessionTitle)
            putExtra(EXTRA_NOTIFY_TITLE, title)
        }
        val followUpReplyPi = PendingIntent.getBroadcast(
            context,
            notifId + 300,
            followUpReplyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        val followUpReplyAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "Reply",
            followUpReplyPi
        ).addRemoteInput(
            RemoteInput.Builder(KEY_TEXT_REPLY).setLabel("Reply to Hermes").build()
        ).build()

        val builder = NotificationCompat.Builder(context, REPLY_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .addAction(followUpReplyAction)

        // Indicate which session this reply belongs to.
        if (sessionTitle.isNotEmpty()) {
            builder.setSubText("Session: $sessionTitle")
        } else if (sessionId.isNotEmpty()) {
            builder.setSubText("Session: $sessionId")
        }

        manager.notify(notifId, builder.build())
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(REPLY_CHANNEL, "Hermes Replies", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS) // hermes -z can take a while
        .build()
}
