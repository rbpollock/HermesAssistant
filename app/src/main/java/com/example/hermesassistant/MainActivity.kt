package com.example.hermesassistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

/**
 * UI-only layer (Phase 2): renders [AssistantUiState] from
 * [AssistantViewModel] and forwards user actions to it. All business
 * logic — networking, audio, speech, history, notifications — lives in
 * the ViewModel.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: AssistantViewModel by viewModels()

    // UI
    private lateinit var statusText: TextView
    private lateinit var subText: TextView
    private lateinit var speakButton: Button
    private lateinit var statusRing: StatusRingView
    private lateinit var historyList: LinearLayout
    private lateinit var historyScroll: android.widget.ScrollView
    private lateinit var typeToggleButton: ImageButton
    private lateinit var textInputRow: LinearLayout
    private lateinit var textInput: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var sessionChipsRow: LinearLayout
    private lateinit var sessionChipsScroll: android.widget.HorizontalScrollView
    private lateinit var settingsButton: ImageButton
    private lateinit var chatSection: View
    private lateinit var assistantPanel: View
    private lateinit var panelCollapseBar: View
    private lateinit var panelToggleButton: ImageButton
    private lateinit var panelToggleLabel: TextView
    private lateinit var panelStripStatus: TextView
    private lateinit var panelStripSpeakButton: Button

    private var panelCollapsed = false
    // True when the panel was collapsed BY TYPING (so closing the typing
    // row restores the pre-typing state instead of staying collapsed).
    private var panelCollapsedForTyping = false
    private var navBarInsetBottom = 0
    private var lastRenderedMessages = emptyList<ChatMessage>()

    private val serviceReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                HermesForegroundService.ACTION_WAKE_WORD -> viewModel.onWakeWordHeard()
                HermesForegroundService.ACTION_DICTATION_RESULT -> {
                    val text = intent.getStringExtra(HermesForegroundService.EXTRA_DICTATION_TEXT).orEmpty()
                    val alreadySent = intent.getBooleanExtra(HermesForegroundService.EXTRA_DICTATION_ALREADY_SENT, false)
                    viewModel.handleDictatedText(text, alreadySent)
                }
                NotificationReplyReceiver.ACTION_HISTORY_UPDATED -> {
                    // Inline reply from the shade appended to the shared
                    // chat_history.json — reload so it shows up here.
                    viewModel.reloadHistory()
                }
                HermesForegroundService.ACTION_REPLY_READY -> {
                    val replyText = intent.getStringExtra(HermesForegroundService.EXTRA_REPLY_TEXT).orEmpty()
                    viewModel.onReplyReady(replyText)
                }
            }
        }
    }

    companion object {
        private const val REQ_POST_NOTIFICATIONS = 2
        private const val REQ_RECORD_AUDIO = 3
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        wireTextInput()
        wirePanelToggle()
        wireSettings()

        observeState()

        // Make the collapsed band clear the navigation bar from the start.
        panelCollapseBar.post { updateNavBarInset() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_RECORD_AUDIO)
        } else {
            HermesForegroundService.notifyMicPermissionGranted()
        }
        requestNotificationPermissionIfNeeded()

        // Auto-start listening whenever the activity loads. The speak
        // button becomes "TAP TO CANCEL" while listening, so the user can
        // always stop it. (Voice-invocation intents hit this same path.)
        viewModel.beginListening()

        if (isUpdateAction(intent?.action)) {
            handleUpdateAction()
        }
        viewModel.handleTargetSessionIntent(
            intent?.getStringExtra("target_session_id").orEmpty(),
            intent?.getStringExtra("target_session_title").orEmpty(),
            intent?.getStringExtra("target_message").orEmpty(),
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isVoiceInvocation(intent.action)) {
            viewModel.beginListening()
        }
        if (isUpdateAction(intent.action)) {
            handleUpdateAction()
        }
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
        HermesForegroundService.hideOverlayIfShown()
        UpdateChecker.checkAndNotify(this)
        viewModel.connectIfNeeded()
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(serviceReceiver) } catch (e: Exception) { /* not registered */ }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) updateNavBarInset()
    }

    // ------------------------------------------------------------------
    // State observation
    // ------------------------------------------------------------------

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    statusText.text = state.status
                    if (::panelStripStatus.isInitialized) {
                        panelStripStatus.text = state.status
                    }
                    statusRing.state = state.statusState
                    subText.text = state.subTextLabel
                    updateSpeakButtons(state.speakButtonLabel)
                    if (state.messages !== lastRenderedMessages || state.messages != lastRenderedMessages) {
                        lastRenderedMessages = state.messages
                        renderHistory()
                    }
                    renderSessionChips(state)
                    // Typing must collapse the panel; listening must expand it.
                    if (state.voiceActive && panelCollapsed && !panelCollapsedForTyping) {
                        panelCollapsed = false
                        applyPanelCollapsed()
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // View binding
    // ------------------------------------------------------------------

    private fun bindViews() {
        statusText = findViewById(R.id.statusText)
        subText = findViewById(R.id.subText)
        speakButton = findViewById(R.id.speakButton)
        statusRing = findViewById(R.id.statusRing)
        historyList = findViewById(R.id.historyList)
        historyScroll = findViewById(R.id.historyScroll)
        typeToggleButton = findViewById(R.id.typeToggleButton)
        textInputRow = findViewById(R.id.textInputRow)
        textInput = findViewById(R.id.textInput)
        sendButton = findViewById(R.id.sendButton)
        sessionChipsRow = findViewById(R.id.sessionChipsRow)
        sessionChipsScroll = findViewById(R.id.sessionChipsScroll)
        settingsButton = findViewById(R.id.settingsButton)
        chatSection = findViewById(R.id.chatSection)
        assistantPanel = findViewById(R.id.assistantPanel)
        panelCollapseBar = findViewById(R.id.panelCollapseBar)
        panelToggleButton = findViewById(R.id.panelToggleButton)
        panelToggleLabel = findViewById(R.id.panelToggleLabel)
        panelStripStatus = findViewById(R.id.panelStripStatus)
        panelStripSpeakButton = findViewById(R.id.panelStripSpeakButton)
    }

    // ------------------------------------------------------------------
    // Panel collapse/expand + nav bar inset
    // ------------------------------------------------------------------

    private fun wirePanelToggle() {
        panelCollapseBar.setOnClickListener { togglePanelCollapsed() }
        panelToggleButton.setOnClickListener { togglePanelCollapsed() }
        panelStripSpeakButton.setOnClickListener { viewModel.onSpeakButtonPressed() }
        panelCollapseBar.post { updateNavBarInset() }
    }

    private fun togglePanelCollapsed() {
        panelCollapsed = !panelCollapsed
        applyPanelCollapsed()
    }

    private fun applyPanelCollapsed() {
        assistantPanel.visibility = if (panelCollapsed) View.GONE else View.VISIBLE
        panelToggleButton.setImageResource(
            if (panelCollapsed) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down
        )
        panelToggleLabel.text = if (panelCollapsed) "Expand panel" else "Collapse panel"
        panelToggleLabel.visibility = if (panelCollapsed) View.GONE else View.VISIBLE
        panelStripStatus.visibility = if (panelCollapsed) View.VISIBLE else View.GONE
        panelStripSpeakButton.visibility = if (panelCollapsed) View.VISIBLE else View.GONE
        val extra = if (panelCollapsed) {
            (resources.displayMetrics.heightPixels * 0.05f).toInt()
        } else 0
        chatSection.setPadding(0, 0, 0, extra)
        updateNavBarInset()
    }

    private fun updateNavBarInset() {
        navBarInsetBottom = try {
            val insets = androidx.core.view.ViewCompat.getRootWindowInsets(panelCollapseBar)
            insets?.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
        } catch (e: Exception) {
            0
        }
        panelCollapseBar.setPadding(0, 0, 0, if (panelCollapsed) navBarInsetBottom else 0)
    }

    private fun expandPanel() {
        if (panelCollapsed) {
            panelCollapsed = false
            applyPanelCollapsed()
        }
    }

    // ------------------------------------------------------------------
    // Text input + settings
    // ------------------------------------------------------------------

    private fun wireTextInput() {
        speakButton.setOnClickListener { viewModel.onSpeakButtonPressed() }

        val iconSize = (resources.displayMetrics.widthPixels * 0.12f).toInt()
        typeToggleButton.layoutParams = typeToggleButton.layoutParams.apply { width = iconSize; height = iconSize }
        typeToggleButton.setOnClickListener {
            val visible = textInputRow.visibility == View.VISIBLE
            textInputRow.visibility = if (visible) View.GONE else View.VISIBLE
            if (visible) {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.hideSoftInputFromWindow(textInput.windowToken, 0)
                if (panelCollapsedForTyping) {
                    panelCollapsedForTyping = false
                    panelCollapsed = false
                    applyPanelCollapsed()
                }
            } else {
                if (!panelCollapsed) {
                    panelCollapsed = true
                    panelCollapsedForTyping = true
                    applyPanelCollapsed()
                }
                textInput.requestFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.showSoftInput(textInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }

        sendButton.setOnClickListener { sendTypedText() }
        textInput.setOnEditorActionListener { _, _, _ ->
            sendTypedText()
            true
        }
    }

    private fun sendTypedText() {
        val text = textInput.text.toString().trim()
        if (text.isEmpty()) return
        textInput.setText("")
        viewModel.sendUserMessage(text)
    }

    private fun wireSettings() {
        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    // ------------------------------------------------------------------
    // Rendering (pure functions of state)
    // ------------------------------------------------------------------

    private fun updateSpeakButtons(label: String) {
        speakButton.text = label
        if (::panelStripSpeakButton.isInitialized) {
            panelStripSpeakButton.text = if (label.length > 12) "CANCEL" else "SPEAK"
        }
    }

    private fun renderHistory() {
        historyList.removeAllViews()
        viewModel.uiState.value.messages.forEach { addBubble(it) }
        historyScroll.post { historyScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun addBubble(m: ChatMessage) {
        val bubbleMaxWidth = (resources.displayMetrics.widthPixels * 0.8f).toInt()
        val sessionAccent = sessionColor(m.sessionId)
        val tv = TextView(this).apply {
            val suffix = if (m.queued) "\n\u23F3 queued (offline)" else ""
            val base = m.text + suffix
            if (m.role == "user" && m.injected) {
                val spannable = android.text.SpannableString(base + "  \u2714")
                spannable.setSpan(
                    android.text.style.ForegroundColorSpan(0xFF34D399.toInt()),
                    base.length,
                    spannable.length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                text = spannable
            } else {
                text = base
            }
            textSize = 14f
            setTextColor(0xFFE5E7EB.toInt())
            setPadding(dp(14), dp(10), dp(14), dp(10))
            maxWidth = bubbleMaxWidth
            isClickable = true
            isFocusable = true
            setOnClickListener {
                viewModel.selectSessionFromMessage(m)
            }
        }

        val bg = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
        }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) }

        when (m.role) {
            "user" -> {
                bg.setColor(if (m.queued) 0xFF3B2F1A.toInt() else 0xFF16233D.toInt())
                lp.gravity = android.view.Gravity.END
            }
            "hermes" -> {
                bg.setColor(0xFF1E293B.toInt())
                lp.gravity = android.view.Gravity.START
            }
            else -> { // notify
                bg.setColor(0xFF111827.toInt())
                lp.gravity = android.view.Gravity.CENTER
                tv.setTextColor(0xFF93C5FD.toInt())
                tv.textSize = 13f
            }
        }
        bg.setStroke(if (m.role == "notify") dp(1) else dp(2), sessionAccent)
        tv.background = bg
        historyList.addView(tv, lp)
    }

    private fun renderSessionChips(state: AssistantUiState) {
        sessionChipsRow.removeAllViews()
        if (state.sessions.isEmpty()) {
            sessionChipsRow.visibility = View.GONE
            return
        }
        sessionChipsRow.visibility = View.VISIBLE
        state.sessions.forEach { session ->
            val selected = session.id == state.replySessionId
            val color = sessionColor(session.id)
            val chip = TextView(this).apply {
                text = session.title.ifEmpty { "…${session.id.takeLast(10)}" }
                textSize = 12f
                setTextColor(if (selected) 0xFF0D0F14.toInt() else 0xFFE5E7EB.toInt())
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = chipBackground(selected, color)
                isClickable = true
                setOnClickListener {
                    viewModel.selectSession(if (selected) "" else session.id, if (selected) "" else session.title)
                }
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
            sessionChipsRow.addView(chip, lp)
        }
        sessionChipsScroll.post {
            val selIndex = state.sessions.indexOfFirst { it.id == state.replySessionId }
            if (selIndex >= 0 && selIndex < sessionChipsRow.childCount) {
                val child = sessionChipsRow.getChildAt(selIndex)
                sessionChipsScroll.smoothScrollTo(child.left, 0)
            }
        }
    }

    private fun chipBackground(selected: Boolean, color: Int) = GradientDrawable().apply {
        cornerRadius = dp(18).toFloat()
        if (selected) {
            setColor(color)
        } else {
            setColor(0xFF111827.toInt())
            setStroke(dp(1), color)
        }
    }

    private val sessionPalette = intArrayOf(
        0xFF60A5FA.toInt(), // blue
        0xFF34D399.toInt(), // green
        0xFFFBBF24.toInt(), // amber
        0xFFF472B6.toInt(), // pink
        0xFFA78BFA.toInt(), // violet
        0xFFF87171.toInt(), // red
        0xFF2DD4BF.toInt(), // teal
        0xFFFB923C.toInt(), // orange
        0xFFA3E635.toInt(), // lime
        0xFF38BDF8.toInt(), // sky
    )

    private fun sessionColor(sessionId: String): Int {
        if (sessionId.isEmpty()) return sessionPalette[0]
        return sessionPalette[Math.floorMod(sessionId.hashCode(), sessionPalette.size)]
    }

    // ------------------------------------------------------------------
    // Permissions / updates
    // ------------------------------------------------------------------

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_POST_NOTIFICATIONS)
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

    private fun isVoiceInvocation(action: String?): Boolean {
        return action == Intent.ACTION_ASSIST ||
            action == Intent.ACTION_VOICE_COMMAND ||
            action == "com.example.hermesassistant.START_LISTENING"
    }

    private fun isUpdateAction(action: String?): Boolean {
        return action == UpdateChecker.ACTION_UPDATE
    }

    private fun handleUpdateAction() {
        val apkUrl = intent.getStringExtra("update_apk_url").orEmpty()
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
                versionName = intent.getStringExtra("update_version").orEmpty(),
                apkUrl = apkUrl,
            ))
            runOnUiThread { viewModel.setStatus(result, StatusRingView.State.CONNECTED) }
        }.start()
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
