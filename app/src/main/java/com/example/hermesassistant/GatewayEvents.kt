package com.example.hermesassistant

/**
 * tui_gateway event type constants (GatewayEventName in the desktop's
 * apps/shared). Events arrive as {method:"event", params:{type, payload}}.
 */
object GatewayEvents {
    const val READY = "gateway.ready"
    const val SESSION_INFO = "session.info"
    const val SESSION_TITLE = "session.title"
    const val SESSION_USAGE = "session.usage"
    const val SESSIONS_CHANGED = "sessions.changed"

    const val MESSAGE_START = "message.start"
    const val MESSAGE_DELTA = "message.delta"
    const val MESSAGE_INTERIM = "message.interim"
    const val MESSAGE_COMPLETE = "message.complete"

    const val THINKING_DELTA = "thinking.delta"
    const val REASONING_DELTA = "reasoning.delta"
    const val REASONING_AVAILABLE = "reasoning.available"

    const val STATUS_UPDATE = "status.update"

    const val TOOL_START = "tool.start"
    const val TOOL_PROGRESS = "tool.progress"
    const val TOOL_COMPLETE = "tool.complete"
    const val TOOL_GENERATING = "tool.generating"

    const val CLARIFY_REQUEST = "clarify.request"
    const val APPROVAL_REQUEST = "approval.request"
    const val SUDO_REQUEST = "sudo.request"
    const val SECRET_REQUEST = "secret.request"
    const val BACKGROUND_COMPLETE = "background.complete"

    const val ERROR = "error"
    const val SKIN_CHANGED = "skin.changed"
}
