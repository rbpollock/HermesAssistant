package com.example.hermesassistant.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.hermesassistant.GatewayMsg
import com.example.hermesassistant.GatewayPart
import com.example.hermesassistant.ToolStatus
import androidx.compose.foundation.layout.PaddingValues as ComposePaddingValues

/**
 * Desktop-ported transcript UI (spec extracted from apps/desktop/src:
 * thread.tsx, tool-fallback.tsx, styles.css). Rules mirrored:
 *   - assistant = plain full-width prose on the chat background (no bubble)
 *   - user = right-aligned 12px-radius tinted bubble
 *   - tools = 11px title rows, spinner while running, silent on success,
 *     collapsible mono body
 *   - thinking = "Thinking" disclosure, auto-open while streaming,
 *     auto-collapse when done (first explicit toggle wins)
 *   - "Hermes is thinking" status row while awaiting the first payload
 *   - auto-follow while at the bottom, jump-to-bottom when scrolled up
 */

@Composable
fun GatewayTranscriptList(
    messages: List<GatewayMsg>,
    turnBusy: Boolean,
    turnAwaitingResponse: Boolean,
    needsInput: Boolean,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    // Follow the stream only while the user is at the bottom (desktop
    // use-stick-to-bottom semantics).
    val follow by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= messages.lastIndex - 1
        }
    }
    LaunchedEffect(messages, turnBusy, turnAwaitingResponse) {
        if (follow && messages.isNotEmpty()) {
            listState.scrollToItem(messages.lastIndex)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = ComposePaddingValues(top = 4.dp, bottom = 8.dp),
    ) {
        if (messages.isEmpty() && !turnBusy) {
            item(key = "empty") { EmptyTranscriptHint() }
        }
        items(messages, key = { it.id }) { msg ->
            when (msg.role) {
                "user" -> UserBubbleView(msg)
                "assistant" -> AssistantMessageView(msg)
                else -> SystemNoteView(msg)
            }
        }
        // Status row: visible while a turn is running but the last
        // assistant message has nothing to show yet (desktop loader +
        // stall indicator territory).
        if (turnBusy && lastAssistantEmpty(messages)) {
            item(key = "status") { ThinkingStatusRow(needsInput) }
        }
    }
}

private fun lastAssistantEmpty(messages: List<GatewayMsg>): Boolean {
    val last = messages.lastOrNull() ?: return true
    if (last.role != "assistant") return true
    return last.parts.none { p ->
        (p is GatewayPart.Text && p.text.isNotBlank()) ||
            (p is GatewayPart.Tool) ||
            (p is GatewayPart.Reasoning && p.text.isNotBlank())
    }
}

@Composable
private fun EmptyTranscriptHint() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "This conversation runs on the Hermes gateway.\nSay \"Hey Hermes\" or type below.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun ThinkingStatusRow(needsInput: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(
                    if (needsInput) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                )
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (needsInput) "Hermes needs your input" else "Hermes is thinking…",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun UserBubbleView(msg: GatewayMsg) {
    val text = msg.parts.filterIsInstance<GatewayPart.Text>().joinToString("") { it.text }
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(
            shape = RoundedCornerShape(12.dp), // desktop rounded-xl
            color = Color(0xFF1B3A6B), // desktop dark user bubble ~#143B91
            border = BorderStroke(1.dp, Color(0xFF2D4E85)),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Text(
                text = text,
                color = Color(0xFFE5E7EB),
                fontSize = 13.sp,
                lineHeight = 19.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun AssistantMessageView(msg: GatewayMsg) {
    Column(modifier = Modifier.fillMaxWidth()) {
        msg.parts.forEach { part ->
            when (part) {
                is GatewayPart.Reasoning -> ThinkingDisclosure(part.text, streaming = msg.pending)
                is GatewayPart.Tool -> ToolRowView(part, streaming = msg.pending)
                is GatewayPart.Text -> {
                    if (part.text.isNotBlank()) {
                        Text(
                            text = part.text,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 13.sp, // --conversation-text-font-size
                            lineHeight = 19.sp,
                            modifier = Modifier.padding(start = 12.dp, top = 2.dp), // --message-text-indent
                        )
                    }
                }
            }
        }
        msg.error?.let { err ->
            Text(
                text = err,
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 12.dp, top = 2.dp),
            )
        }
    }
}

/** Desktop ThinkingDisclosure: auto-open while streaming, collapse when done. */
@Composable
private fun ThinkingDisclosure(text: String, streaming: Boolean) {
    var open by remember { mutableStateOf(true) }
    var userToggled by remember { mutableStateOf(false) }
    val expanded = if (userToggled) open else streaming

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                userToggled = true
                open = !open
            }
            .padding(top = 4.dp, bottom = 2.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (streaming) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.5.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = if (expanded) "▾" else "▸",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                modifier = Modifier.size(12.dp),
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = "Thinking",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp, // --conversation-tool-font-size
            fontWeight = FontWeight.Medium,
        )
    }
    if (expanded && text.isNotBlank()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 160.dp) // desktop thinking-preview max-h-40
                .verticalScroll(rememberScrollState())
                .padding(start = 18.dp, end = 4.dp, bottom = 2.dp),
        ) {
            Text(
                text = text,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
        }
    }
}

/** Desktop ToolEntry: one row per tool call, collapsible mono body. */
@Composable
private fun ToolRowView(tool: GatewayPart.Tool, streaming: Boolean) {
    var open by remember { mutableStateOf(false) }
    val running = tool.status == ToolStatus.RUNNING && streaming
    val hasBody = tool.resultText.isNotEmpty() || tool.argsText.isNotEmpty()
    val isError = tool.status == ToolStatus.ERROR

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = hasBody) { open = !open }
                .padding(start = 12.dp, top = 3.dp, bottom = 3.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                running -> androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                isError -> Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error)
                )
                else -> Spacer(Modifier.size(12.dp)) // silent success (desktop)
            }
            Spacer(Modifier.width(6.dp))
            Text(
                text = toolTitle(tool),
                color = if (isError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (tool.durationLabel.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = tool.durationLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (hasBody) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (open) "▾" else "▸",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    fontSize = 10.sp,
                )
            }
        }
        if (open && hasBody) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, top = 2.dp, bottom = 4.dp, end = 8.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Text(
                    text = tool.resultText.ifEmpty { tool.argsText },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 12,
                )
            }
        }
    }
}

private fun toolTitle(tool: GatewayPart.Tool): String {
    val name = tool.toolName.lowercase()
    val running = tool.status == ToolStatus.RUNNING
    return when {
        name == "terminal" || name == "execute_code" ->
            if (running) "Running code" else "Ran code"
        name.startsWith("browser") || name.startsWith("web") ->
            if (running) "Browsing…" else "Browsed"
        else -> name.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}

@Composable
private fun SystemNoteView(msg: GatewayMsg) {
    val text = msg.parts.filterIsInstance<GatewayPart.Text>().joinToString("") { it.text }
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        fontSize = 11.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
    )
}

/**
 * Desktop composer port: multi-line input, Enter sends, and the primary
 * button becomes a Stop while the turn is busy (desktop busyAction rule).
 */
@Composable
fun ComposerBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onMic: () -> Unit,
    onStop: () -> Unit,
    busy: Boolean,
    voiceActive: Boolean,
    hasParkedAudio: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            placeholder = {
                Text(
                    text = if (busy) "Send a follow-up…" else "Give Hermes a task…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            singleLine = false,
            maxLines = 4,
            shape = RoundedCornerShape(16.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = MaterialTheme.colorScheme.primary,
            ),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { onSend() }),
        )
        Spacer(Modifier.width(8.dp))
        // Mic / stop-listening / play-parked (same triple as MicActionButton).
        val (micIcon, micDesc, micTint) = when {
            voiceActive -> Triple(
                Icons.Default.Stop,
                "Stop listening",
                MaterialTheme.colorScheme.error,
            )
            hasParkedAudio -> Triple(
                Icons.Default.PlayArrow,
                "Play response",
                MaterialTheme.colorScheme.primary,
            )
            else -> Triple(
                Icons.Default.Mic,
                "Tap to speak",
                MaterialTheme.colorScheme.primary,
            )
        }
        IconButton(onClick = onMic) {
            Icon(imageVector = micIcon, contentDescription = micDesc, tint = micTint)
        }
        // Primary: send arrow -> stop square while busy (desktop rule:
        // busyAction = queue/stop takeover; typing while busy queues).
        if (busy) {
            IconButton(onClick = onStop) {
                Icon(
                    imageVector = Icons.Default.Stop,
                    contentDescription = "Stop",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        } else {
            IconButton(onClick = onSend) {
                Icon(
                    imageVector = Icons.Default.Send,
                    contentDescription = "Send",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
