package com.example.hermesassistant

/**
 * Gateway transcript state + reducer — a direct port of the desktop app's
 * chat model (apps/desktop/src/lib/chat-messages.ts + the event map in
 * use-message-stream.ts). The streaming message is the last array element,
 * identified by turn.streamId, with pending=true; deltas append to its
 * parts. Tool events upsert tool parts immediately; text/reasoning deltas
 * are batched by the ViewModel before being applied here.
 */
sealed class GatewayPart {
    data class Text(val text: String) : GatewayPart()
    data class Reasoning(val text: String) : GatewayPart()
    data class Tool(
        val toolCallId: String,
        val toolName: String,
        val argsText: String,
        val status: ToolStatus,
        val resultText: String = "",
        val durationLabel: String = "",
    ) : GatewayPart()
}

enum class ToolStatus { RUNNING, COMPLETE, ERROR }

data class GatewayMsg(
    val id: String,
    val role: String, // "user" | "assistant" | "system"
    val parts: List<GatewayPart> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
    val pending: Boolean = false,
    val error: String? = null,
)

data class GatewayTurnState(
    val busy: Boolean = false,
    val awaitingResponse: Boolean = false,
    val streamId: String? = null,
    val interrupted: Boolean = false,
    val needsInput: Boolean = false,
    val turnStartedAt: Long = 0L,
)

class GatewayTranscript {

    var messages: List<GatewayMsg> = emptyList()
        private set

    var turn: GatewayTurnState = GatewayTurnState()
        private set

    private fun mutableLast(): GatewayMsg? =
        messages.lastOrNull()?.takeIf { it.pending }

    private fun replaceLast(transform: (GatewayMsg) -> GatewayMsg) {
        if (messages.isEmpty()) return
        val idx = messages.lastIndex
        messages = messages.toMutableList().also { it[idx] = transform(it[idx]) }
    }

    /** Desktop: message.start -> busy, awaitingResponse, new pending message. */
    fun onMessageStart() {
        if (messages.lastOrNull()?.pending == true) {
            // A second start mid-stream: finalize whatever was pending.
            replaceLast { it.copy(pending = false) }
        }
        val streamId = "assistant-stream-${System.currentTimeMillis()}"
        val msg = GatewayMsg(id = streamId, role = "assistant", pending = true)
        messages = messages + msg
        turn = GatewayTurnState(
            busy = true,
            awaitingResponse = true,
            streamId = streamId,
            turnStartedAt = System.currentTimeMillis(),
        )
    }

    fun addUserMessage(text: String) {
        // Finalize any pending message first (a user send mid-stream means
        // the previous turn ended without message.complete).
        if (messages.lastOrNull()?.pending == true) {
            replaceLast { it.copy(pending = false) }
        }
        messages = messages + GatewayMsg(
            id = "user-${System.currentTimeMillis()}",
            role = "user",
            parts = listOf(GatewayPart.Text(text)),
        )
        turn = turn.copy(awaitingResponse = false, streamId = null, interrupted = false)
    }

    /** Desktop: appendAssistantTextPart — append to the last Text part. */
    fun appendTextDelta(delta: String) {
        if (delta.isEmpty() || turn.interrupted) return
        val last = mutableLast() ?: return
        val parts = last.parts.toMutableList()
        val lastText = parts.lastOrNull() as? GatewayPart.Text
        if (lastText != null) {
            parts[parts.lastIndex] = GatewayPart.Text(lastText.text + delta)
        } else {
            parts.add(GatewayPart.Text(delta))
        }
        replaceLast { it.copy(parts = parts) }
        turn = turn.copy(awaitingResponse = false)
    }

    /** Desktop: appendReasoningPart / reasoning.available (replace=true). */
    fun appendReasoningDelta(delta: String, replace: Boolean = false) {
        if (delta.isEmpty() || turn.interrupted) return
        val last = mutableLast() ?: return
        val parts = last.parts.toMutableList()
        val idx = parts.indexOfLast { it is GatewayPart.Reasoning }
        if (replace && idx >= 0) {
            parts[idx] = GatewayPart.Reasoning(delta)
        } else if (idx >= 0) {
            val r = parts[idx] as GatewayPart.Reasoning
            parts[idx] = GatewayPart.Reasoning(r.text + delta)
        } else {
            parts.add(GatewayPart.Reasoning(delta))
        }
        replaceLast { it.copy(parts = parts) }
        turn = turn.copy(awaitingResponse = false)
    }

    /**
     * Desktop: upsertToolPart — match by tool id, else same-name+context,
     * else oldest-pending (completion) / most-recent-pending (progress).
     */
    fun upsertTool(name: String, toolId: String, argsText: String, phase: ToolStatus, resultText: String = "", durationLabel: String = "") {
        if (turn.interrupted && phase == ToolStatus.RUNNING) return
        val last = mutableLast() ?: return
        val parts = last.parts.toMutableList()
        val toolIdx = parts.indexOfLast { it is GatewayPart.Tool && it.toolCallId == toolId }
        val idx = if (toolIdx >= 0) {
            toolIdx
        } else {
            // No id match: same-name pending tool gets completed; otherwise
            // most-recent pending (running) / oldest pending (complete).
            val pending = parts.withIndex().filter { (_, p) -> p is GatewayPart.Tool && p.status == ToolStatus.RUNNING }
            when {
                pending.isEmpty() -> -1
                phase == ToolStatus.RUNNING -> pending.last().index
                else -> pending.first().index
            }
        }
        if (idx >= 0 && parts[idx] is GatewayPart.Tool) {
            val old = parts[idx] as GatewayPart.Tool
            parts[idx] = old.copy(
                status = phase,
                toolName = name,
                argsText = argsText.ifEmpty { old.argsText },
                resultText = resultText.ifEmpty { old.resultText },
                durationLabel = durationLabel.ifEmpty { old.durationLabel },
            )
        } else {
            parts.add(
                GatewayPart.Tool(
                    toolCallId = toolId,
                    toolName = name,
                    argsText = argsText,
                    status = phase,
                    resultText = resultText,
                    durationLabel = durationLabel,
                )
            )
        }
        replaceLast { it.copy(parts = parts) }
        turn = turn.copy(awaitingResponse = false)
    }

    /** Desktop: message.complete — replace text with the final text, settle. */
    fun onMessageComplete(finalText: String?) {
        if (messages.lastOrNull()?.pending != true) return
        replaceLast { m ->
            val parts = if (finalText.isNullOrEmpty()) {
                m.parts
            } else {
                // Dedupe: one final Text part, keep reasoning + tools.
                m.parts.filterNot { it is GatewayPart.Text } + GatewayPart.Text(finalText)
            }
            m.copy(parts = parts, pending = false, error = null)
        }
        turn = turn.copy(busy = false, awaitingResponse = false, streamId = null)
    }

    /** Desktop: failAssistantMessage (error event). */
    fun fail(message: String) {
        if (messages.lastOrNull()?.pending == true) {
            replaceLast { it.copy(pending = false, error = message) }
        }
        turn = turn.copy(busy = false, awaitingResponse = false, streamId = null)
    }

    /** Desktop: Stop/Esc — drop all late deltas/tool events. */
    fun interrupt() {
        turn = turn.copy(interrupted = true)
    }

    fun setNeedsInput(value: Boolean) {
        turn = turn.copy(needsInput = value)
    }

    fun clear() {
        messages = emptyList()
        turn = GatewayTurnState()
    }
}
