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
        private const val SERVER = "http://100.123.127.108:8000"
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
            notifyReply(context, "Reply not sent", "Empty message", sessionId)
            return
        }

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
                    .url("$SERVER/chat/message")
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    val respBody = response.body?.string().orEmpty()
                    val json = JSONObject(respBody)
                    if (response.isSuccessful && json.optBoolean("ok", false)) {
                        val reply = json.optString("reply", "")
                        notifyReply(context, "Hermes replied", reply, sessionId)
                    } else {
                        notifyReply(context, "Reply not sent", json.optString("error", "server error"), sessionId)
                    }
                }
            } catch (e: Exception) {
                notifyReply(context, "Reply not sent", "Network error: ${e.message}", sessionId)
            } finally {
                pendingResult.finish()
            }
        }.start()
    }

    private fun notifyReply(context: Context, title: String, message: String, sessionId: String) {
        ensureChannel(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Tapping the follow-up opens the app at the same session chip
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("target_session_id", sessionId)
            putExtra("target_session_title", title)
        }
        val pi = PendingIntent.getActivity(
            context,
            sessionId.hashCode() + 1,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, REPLY_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        manager.notify(sessionId.hashCode() + 1, notification)
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
