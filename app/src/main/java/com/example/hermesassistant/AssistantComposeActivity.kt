package com.example.hermesassistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.hermesassistant.ui.AssistantScreen
import com.example.hermesassistant.ui.SettingsScreen
import kotlinx.coroutines.launch

/**
 * Phase-3 host for the Compose assistant surface (the bottom-sheet
 * invocation UI). Shares the app-scoped [AssistantViewModel] with the
 * legacy MainActivity — one WebSocket, one audio stack, one state.
 */
class AssistantComposeActivity : ComponentActivity() {

    private val viewModel: AssistantViewModel by viewModels { AppViewModelProvider.factory }
    private var showSettings by mutableStateOf(false)

    companion object {
        private const val REQ_POST_NOTIFICATIONS = 2
        private const val REQ_RECORD_AUDIO = 3
    }

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
            if (showSettings) {
                SettingsScreen(
                    onBack = { showSettings = false },
                    onServerChanged = { viewModel.reconfigureServer() },
                )
            } else {
                AssistantScreen(
                    state = state.value,
                    onSpeak = { viewModel.onSpeakButtonPressed() },
                    onSendText = { viewModel.sendUserMessage(it) },
                    onSelectSession = { viewModel.selectSession(it.id, it.title) },
                    onSettings = { showSettings = true },
                )
            }
        }

        // Auto-start listening on invocation (same as the legacy activity).
        viewModel.beginListening()
        viewModel.handleTargetSessionIntent(
            intent?.getStringExtra("target_session_id").orEmpty(),
            intent?.getStringExtra("target_session_title").orEmpty(),
            intent?.getStringExtra("target_message").orEmpty(),
        )

        // Mic permission bootstrap: without RECORD_AUDIO the service's
        // wake word cannot start. This mirrors MainActivity's onCreate —
        // the Compose surface is now a primary entry point, so it must
        // ask too (fresh installs that land here never saw the prompt).
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            androidx.core.app.ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_RECORD_AUDIO
            )
        } else {
            HermesForegroundService.notifyMicPermissionGranted()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            androidx.core.app.ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_POST_NOTIFICATIONS
            )
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                HermesForegroundService.notifyMicPermissionGranted()
            }
        }
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
