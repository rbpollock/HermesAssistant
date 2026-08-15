package com.example.hermesassistant

import android.content.Context
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Settings screen: configure the home server (ip|port or hostname|port)
 * that the app connects to for the voice chat WebSocket, HTTP chat
 * fallback, and notification relay.
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val hostInput = findViewById<EditText>(R.id.settingsHostInput)
        val portInput = findViewById<EditText>(R.id.settingsPortInput)
        val saveButton = findViewById<Button>(R.id.settingsSaveButton)
        val statusText = findViewById<TextView>(R.id.settingsStatus)

        // Pre-fill current values
        hostInput.setText(ServerConfig.host(this))
        portInput.setText(ServerConfig.port(this))

        saveButton.setOnClickListener {
            val host = hostInput.text.toString().trim()
            val port = portInput.text.toString().trim()
            if (host.isEmpty() || port.isEmpty()) {
                statusText.text = "Host and port are required."
                return@setOnClickListener
            }
            ServerConfig.save(this, host, port)
            statusText.text = "Saved: ${ServerConfig.httpBase(this)}"

            // Hide keyboard so the user can see the confirmation
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(hostInput.windowToken, 0)
        }
    }
}
