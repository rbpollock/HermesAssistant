package com.example.hermesassistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
                    state = state.value,
                )
            } else {
                AssistantScreen(
                    state = state.value,
                    onSpeak = { viewModel.onSpeakButtonPressed() },
                    onSendText = { viewModel.sendUserMessage(it) },
                    onSelectSession = { viewModel.selectSession(it.id, it.title) },
                    onSelectMessage = { viewModel.selectSessionFromMessage(it) },
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
        if (isUpdateAction(intent?.action)) {
            handleUpdateAction(intent)
        }

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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent) // keep getIntent() in sync for singleTask relaunch
        if (isVoiceInvocation(intent.action)) {
            viewModel.beginListening()
        }
        if (isUpdateAction(intent.action)) {
            handleUpdateAction(intent)
        }
        viewModel.handleTargetSessionIntent(
            intent.getStringExtra("target_session_id").orEmpty(),
            intent.getStringExtra("target_session_title").orEmpty(),
            intent.getStringExtra("target_message").orEmpty(),
        )
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(serviceReceiver) } catch (e: Exception) { /* not registered */ }
    }

    // ------------------------------------------------------------------
    // Update action (from the update notification / UpdateChecker)
    // ------------------------------------------------------------------

    private fun isVoiceInvocation(action: String?): Boolean {
        return action == Intent.ACTION_ASSIST ||
            action == Intent.ACTION_VOICE_COMMAND ||
            action == "com.example.hermesassistant.START_LISTENING"
    }

    private fun isUpdateAction(action: String?): Boolean {
        return action == UpdateChecker.ACTION_UPDATE
    }

    private fun handleUpdateAction(intent: Intent?) {
        val apkUrl = intent?.getStringExtra("update_apk_url").orEmpty()
        if (apkUrl.isEmpty()) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.RELEASE_PAGE_URL)))
            return
        }
        if (!UpdateChecker.canRequestInstalls(this)) {
            viewModel.setStatus("Allow installs from this app, then retry", StatusRingView.State.CONNECTED)
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.RELEASE_PAGE_URL)))
            }
            return
        }
        viewModel.setStatus("Downloading update...", StatusRingView.State.THINKING)
        Thread {
            val result = UpdateChecker.installRelease(this, UpdateChecker.ReleaseInfo(
                versionName = intent?.getStringExtra("update_version").orEmpty(),
                apkUrl = apkUrl,
            ))
            runOnUiThread { viewModel.setStatus(result, StatusRingView.State.CONNECTED) }
        }.start()
    }
}
