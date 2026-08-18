package com.example.hermesassistant.ui

import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.animateTo
import com.example.hermesassistant.AssistantUiState
import com.example.hermesassistant.ChatMessage
import com.example.hermesassistant.KnownSession
import com.example.hermesassistant.StatusRingView
import com.example.hermesassistant.ui.theme.HermesTheme
import kotlinx.coroutines.launch

/** Sheet positions: peek (at rest) -> half -> full. */
private enum class SheetValue { PEEK, HALF, FULL }

private fun orbStateOf(state: AssistantUiState): OrbState = when (state.statusState) {
    StatusRingView.State.LISTENING -> OrbState.LISTENING
    StatusRingView.State.THINKING -> OrbState.THINKING
    StatusRingView.State.SPEAKING -> OrbState.SPEAKING
    else -> OrbState.IDLE
}

/**
 * Phase-3 Assistant invocation surface: a bottom sheet with peek/half/full
 * drag states, a dimming scrim, spring-like expansion, the audio-reactive
 * orb, session chips, and the typed-text row — one coherent surface.
 *
 * Hand-rolled with AnchoredDraggable (zero extra dependencies; the brief
 * explicitly allows this over FlexibleBottomSheet).
 */
@Composable
fun AssistantScreen(
    state: AssistantUiState,
    onSpeak: () -> Unit,
    onSendText: (String) -> Unit,
    onSelectSession: (KnownSession) -> Unit,
    onSelectMessage: (ChatMessage) -> Unit,
    onSettings: () -> Unit,
) {
    HermesTheme {
        val density = LocalDensity.current
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0D0F14))
        ) {
        val screenHeightPx = with(density) { maxHeight.toPx() }
        // Anchor values = position of the sheet's TOP edge (translation
        // of a top-aligned full-screen sheet). Previously these were
        // inverted: PEEK at 16% meant 84% of the sheet was visible and
        // FULL at 94% meant the sheet nearly vanished. Corrected:
        //   PEEK -> top edge near the bottom (slim bottom strip)
        //   HALF -> top edge at mid-screen
        //   FULL -> top edge near the top (sheet fills the screen)
        val fullAnchor = screenHeightPx * 0.06f
        val halfAnchor = screenHeightPx * 0.5f
        val peekAnchor = screenHeightPx * 0.84f

        val sheetState = remember {
            AnchoredDraggableState(
                initialValue = SheetValue.PEEK,
                anchors = DraggableAnchors {
                    SheetValue.PEEK at peekAnchor
                    SheetValue.HALF at halfAnchor
                    SheetValue.FULL at fullAnchor
                },
                positionalThreshold = { distance -> distance * 0.4f },
                velocityThreshold = { with(density) { 180.dp.toPx() } },
                snapAnimationSpec = tween(durationMillis = 450),
                decayAnimationSpec = androidx.compose.animation.core.exponentialDecay(),
            )
        }

        // Auto-raise to HALF when the user starts listening.
        val isListening = state.voiceActive
        LaunchedEffect(isListening) {
            if (isListening && sheetState.currentValue == SheetValue.PEEK) {
                sheetState.animateTo(SheetValue.HALF)
            }
        }

        // Dim the background behind the sheet (scrim).
        val scope = rememberCoroutineScope()
        val scrimAlpha = 0.55f * (
            when (sheetState.currentValue) {
                SheetValue.PEEK -> 0.2f
                SheetValue.HALF -> 0.6f
                SheetValue.FULL -> 0.85f
            }
            )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0D0F14))
        ) {
            // Scrim (tap to collapse to peek)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { this.alpha = scrimAlpha }
                    .background(Color.Black)
                    .clickable { scope.launch { sheetState.animateTo(SheetValue.PEEK) } }
            )

            // The sheet
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 120.dp)
                    .anchoredDraggable(sheetState, Orientation.Vertical)
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .navigationBarsPadding()
            ) {
                SheetContent(
                    state = state,
                    onSpeak = onSpeak,
                    onSendText = onSendText,
                    onSelectSession = onSelectSession,
                    onSelectMessage = onSelectMessage,
                    onSettings = onSettings,
                    sheetValue = sheetState.currentValue,
                    onExpand = { scope.launch { sheetState.animateTo(SheetValue.FULL) } },
                    onCollapse = { scope.launch { sheetState.animateTo(SheetValue.PEEK) } },
                )
            }
        }
        }
    }
}

@Composable
private fun SheetContent(
    state: AssistantUiState,
    onSpeak: () -> Unit,
    onSendText: (String) -> Unit,
    onSelectSession: (KnownSession) -> Unit,
    onSelectMessage: (ChatMessage) -> Unit,
    onSettings: () -> Unit,
    sheetValue: SheetValue,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
) {
    val orb = orbStateOf(state)
    var textInput by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 20.dp)
    ) {
        // Drag handle
        Box(
            modifier = Modifier
                .padding(top = 10.dp)
                .size(width = 44.dp, height = 5.dp)
                .clip(CircleShape)
                .background(Color(0xFF334155))
                .align(Alignment.CenterHorizontally)
        )

        if (sheetValue == SheetValue.PEEK) {
            // F2: at PEEK the sheet is a slim strip — status + speak
            // button only. The full content (chips, orb, input) would
            // overflow a 16%-height sheet; the "at rest" state should be
            // minimal, not a squashed version of everything.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.status,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        maxLines = 1,
                    )
                    Text(
                        text = state.subTextLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Button(
                    onClick = onSpeak,
                    modifier = Modifier.height(44.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(text = speakLabel(state), fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                }
            }
            return@Column
        }

        // Header row: expand/collapse chevron + session chips + settings
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { if (sheetValue == SheetValue.FULL) onCollapse() else onExpand() }) {
                Icon(
                    imageVector = if (sheetValue == SheetValue.FULL) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                    contentDescription = if (sheetValue == SheetValue.FULL) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }

            SessionChips(
                sessions = state.sessions,
                selectedId = state.replySessionId,
                onSelect = onSelectSession,
                modifier = Modifier.weight(1f),
            )

            IconButton(onClick = onSettings) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        // F10: session header at HALF AND FULL — the glanceable "which
        // session is targeted" confirmation must not wait for FULL.
        SessionHeader(
            sessionId = state.replySessionId,
            sessionTitle = state.replySessionTitle,
            modifier = Modifier.fillMaxWidth(),
        )

        // Listening orb + status (the invocation focus). Hidden in FULL —
        // when the chat is active the history owns the space (the user
        // asked for the orb to disappear once chat is open).
        if (sheetValue != SheetValue.FULL) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ListeningOrb(state = orb, rmsLevel = state.rmsLevel, sizeDp = 140)
                    Spacer(Modifier.height(18.dp))
                    Text(
                        text = state.status,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 18.sp,
                        maxLines = 1,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = state.subTextLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        maxLines = 2,
                    )
                }
            }
        }

        // History (visible when expanded — one coherent surface).
        // Jetchat-style: pinned session header + bottom-pinned list with
        // a jump-to-bottom button when the user has scrolled up.
        if (sheetValue == SheetValue.FULL) {
            val listState = rememberLazyListState()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                MessageList(
                    messages = state.messages,
                    onSelectMessage = onSelectMessage,
                    listState = listState,
                    modifier = Modifier.fillMaxSize(),
                )
                val showJumpToBottom by remember {
                    derivedStateOf {
                        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                        lastVisible < (state.messages.lastIndex)
                    }
                }
                if (showJumpToBottom) {
                    JumpToBottom(
                        onClicked = {
                            scope.launch {
                                listState.animateScrollToItem(state.messages.lastIndex)
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 16.dp, bottom = 8.dp),
                    )
                }
            }
        }

        // Speak button + text input
        Button(
            onClick = onSpeak,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text(text = speakLabel(state), fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        }

        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = textInput,
            onValueChange = { textInput = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Type a message...", color = MaterialTheme.colorScheme.onSurfaceVariant) },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = MaterialTheme.colorScheme.primary,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(
                onSend = {
                    onSendText(textInput.trim())
                    textInput = ""
                    keyboard?.hide()
                }
            ),
            trailingIcon = {
                IconButton(onClick = {
                    onSendText(textInput.trim())
                    textInput = ""
                    keyboard?.hide()
                }) {
                    Icon(
                        imageVector = Icons.Default.Send,
                        contentDescription = "Send",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            },
        )

        Spacer(Modifier.height(12.dp))
    }
}

/** F6: the speak button says what tapping it will DO. A parked response
 *  (BT-only + no headset) must be discoverable from the button itself,
 *  not just the status line. */
@Composable
private fun speakLabel(state: AssistantUiState): String = when {
    state.hasParkedAudio -> "TAP TO PLAY"
    else -> state.speakButtonLabel
}

@Composable
private fun SessionChips(
    sessions: List<KnownSession>,
    selectedId: String,
    onSelect: (KnownSession) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        sessions.forEach { session ->
            val selected = session.id == selectedId
            val color = sessionColor(session.id)
            Surface(
                onClick = { onSelect(session) },
                shape = RoundedCornerShape(18.dp),
                color = if (selected) Color(color) else Color(0xFF111827),
                border = androidx.compose.foundation.BorderStroke(
                    if (selected) 0.dp else 1.dp,
                    Color(color),
                ),
            ) {
                Text(
                    text = session.title.ifEmpty { "…${session.id.takeLast(10)}" },
                    fontSize = 12.sp,
                    color = if (selected) Color(0xFF0D0F14) else Color(0xFFE5E7EB),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun MessageList(
    messages: List<ChatMessage>,
    onSelectMessage: (ChatMessage) -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    // Jetchat-style chat list: LazyColumn pinned to the bottom so the
    // newest message is always visible (chat convention), session-colored
    // bubbles, spacing grouped by message instead of manual margins.
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }
    LazyColumn(
        state = listState,
        modifier = modifier,
        reverseLayout = false,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            top = 4.dp, bottom = 8.dp
        ),
    ) {
        items(
            count = messages.size,
            key = { index -> "${messages[index].sessionId}:${messages[index].text}:$index" },
        ) { index ->
            MessageBubble(messages[index], onSelectMessage)
        }
    }
}

/** Pinned header showing which session is currently targeted (journey 3:
 *  a glanceable confirmation, not buried in text). */
@Composable
private fun SessionHeader(
    sessionId: String,
    sessionTitle: String,
    modifier: Modifier = Modifier,
) {
    val accent = if (sessionId.isEmpty()) Color(0xFF334155) else Color(sessionColor(sessionId))
    val label = if (sessionId.isEmpty()) {
        "Daily phone chat"
    } else {
        sessionTitle.ifEmpty { "…${sessionId.takeLast(10)}" }
    }
    Row(
        modifier = modifier
            .padding(top = 6.dp, bottom = 10.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(accent)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (sessionId.isEmpty()) "No session targeted — " else "Replying to: ",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

/** Small circular button that jumps back to the newest message. */
@Composable
private fun JumpToBottom(
    onClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClicked,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier,
    ) {
        Icon(
            imageVector = Icons.Default.KeyboardArrowDown,
            contentDescription = "Jump to newest",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(8.dp)
                .size(20.dp),
        )
    }
}

@Composable
private fun MessageBubble(m: ChatMessage, onSelect: (ChatMessage) -> Unit) {
    val accent = sessionColor(m.sessionId)
    val bg = when (m.role) {
        "user" -> if (m.queued) Color(0xFF3B2F1A) else Color(0xFF16233D)
        "hermes" -> Color(0xFF1E293B)
        else -> Color(0xFF111827)
    }
    val align = when (m.role) {
        "user" -> Alignment.CenterEnd
        "hermes" -> Alignment.CenterStart
        else -> Alignment.Center
    }
    val suffix = if (m.queued) "\n\u23F3 queued (offline)" else ""
    val base = m.text + suffix
    val shown = if (m.role == "user" && m.injected) "$base  \u2714" else base

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = align) {
        Surface(
            onClick = { onSelect(m) },
            shape = RoundedCornerShape(14.dp),
            color = bg,
            border = androidx.compose.foundation.BorderStroke(
                width = if (m.role == "notify") 1.dp else 2.dp,
                Color(accent),
            ),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Text(
                text = shown,
                fontSize = if (m.role == "notify") 13.sp else 14.sp,
                color = if (m.role == "notify") Color(0xFF93C5FD) else Color(0xFFE5E7EB),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

private val sessionPalette = intArrayOf(
    0xFF60A5FA.toInt(), 0xFF34D399.toInt(), 0xFFFBBF24.toInt(),
    0xFFF472B6.toInt(), 0xFFA78BFA.toInt(), 0xFFF87171.toInt(),
    0xFF2DD4BF.toInt(), 0xFFFB923C.toInt(), 0xFFA3E635.toInt(),
    0xFF38BDF8.toInt(),
)

private fun sessionColor(sessionId: String): Int {
    if (sessionId.isEmpty()) return sessionPalette[0]
    return sessionPalette[Math.floorMod(sessionId.hashCode(), sessionPalette.size)]
}
