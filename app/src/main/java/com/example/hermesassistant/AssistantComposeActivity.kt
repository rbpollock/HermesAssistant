package com.example.hermesassistant

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.hermesassistant.ui.AssistantScreen
import kotlinx.coroutines.launch

/**
 * Phase-3 host for the Compose assistant surface (the bottom-sheet
 * invocation UI). Shares the app-scoped [AssistantViewModel] with the
 * legacy MainActivity — one WebSocket, one audio stack, one state.
 */
class AssistantComposeActivity : ComponentActivity() {

    private val viewModel: AssistantViewModel by viewModels { AppViewModelProvider.factory }

    private val serviceReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                HermesForegroundService.ACTION_WAKE_WORD -> viewModel.onWakeWordHeard()
                HermesForegroundService.ACTION_DICTATION_RESULT -> {
                    val text = intent.getStringExtra(HermesForegroundService.EXTRA_DICTATION_TEXT).orEmpty()
                    val alreadySent = intent.getBooleanExtra(HermesForegroundService.EXTRA_DICTATION_ALREADY_SENT, false)
                    viewModel.handleDictatedText(text, alreadySent)
                }
                HermesForegroundService.ACTION_REPLY_READY -> {
                    viewModel.onReplyReady(intent.getStringExtra(HermesForegroundService.EXTRA_REPLY_TEXT).orEmpty())
                }
                NotificationReplyReceiver.ACTION_HISTORY_UPDATED -> viewModel.reloadHistory()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppViewModelProvider.init(application)

        setContent {
            val state = viewModel.uiState.collectAsState()
            AssistantScreen(
                state = state.value,
                onSpeak = { viewModel.onSpeakButtonPressed() },
                onSendText = { viewModel.sendUserMessage(it) },
                onSelectSession = { viewModel.selectSession(it.id, it.title) },
                onSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
            )
        }

        // Auto-start listening on invocation (same as the legacy activity).
        viewModel.beginListening()
        viewModel.handleTargetSessionIntent(
            intent?.getStringExtra("target_session_id").orEmpty(),
            intent?.getStringExtra("target_session_title").orEmpty(),
            intent?.getStringExtra("target_message").orEmpty(),
        )
    }

    override fun onResume() {
        super.onResume()
        val filter = android.content.IntentFilter().apply {
            addAction(HermesForegroundService.ACTION_WAKE_WORD)
            addAction(HermesForegroundService.ACTION_DICTATION_RESULT)
            addAction(HermesForegroundService.ACTION_REPLY_READY)
            addAction(NotificationReplyReceiver.ACTION_HISTORY_UPDATED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serviceReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(serviceReceiver, filter)
        }
        viewModel.connectIfNeeded()
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(serviceReceiver) } catch (e: Exception) { /* not registered */ }
    }
}
