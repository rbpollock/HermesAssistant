package com.example.hermesassistant

import android.content.Context
import android.content.Intent
import android.service.voice.VoiceInteractionSession
import android.os.Bundle

class HermesVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        
        // When the OS invokes the assistant, launch the Compose surface
        // and tell it to auto-listen
        val intent = Intent(context, AssistantComposeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            action = "com.example.hermesassistant.START_LISTENING"
        }
        context.startActivity(intent)
        
        // Hide the invisible OS session immediately since our Activity handles it
        hide()
    }
}
