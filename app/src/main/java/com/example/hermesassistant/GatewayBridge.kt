package com.example.hermesassistant

/**
 * Process-wide hand-off from the foreground service to the (app-scoped)
 * ViewModel's gateway transport. The FGS runs the wake-word Vosk dictation
 * and may need to submit a phrase while the activity is paused or dead —
 * the broadcast receiver only works when an activity is alive.
 */
object GatewayBridge {
    @Volatile
    var submit: ((String) -> Unit)? = null
}
