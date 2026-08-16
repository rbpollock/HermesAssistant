package com.example.hermesassistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Settings screen: configure the home server (ip|port or hostname|port)
 * that the app connects to for the voice chat WebSocket, HTTP chat
 * fallback, and notification relay. Also shows the current version, a
 * link to the GitHub repo, and a manual update check.
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val hostInput = findViewById<EditText>(R.id.settingsHostInput)
        val portInput = findViewById<EditText>(R.id.settingsPortInput)
        val saveButton = findViewById<Button>(R.id.settingsSaveButton)
        val statusText = findViewById<TextView>(R.id.settingsStatus)
        val versionText = findViewById<TextView>(R.id.settingsVersionText)
        val githubLink = findViewById<TextView>(R.id.settingsGithubLink)
        val backButton = findViewById<ImageButton>(R.id.settingsBackButton)
        val checkUpdateButton = findViewById<Button>(R.id.settingsCheckUpdateButton)

        // Back arrow: return to the main app
        backButton.setOnClickListener { finish() }

        // Current version from the installed package
        versionText.text = "Version ${currentVersion()}"

        // Clickable GitHub link
        githubLink.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.RELEASE_PAGE_URL)))
            } catch (e: Exception) {
                statusText.text = "No browser available"
            }
        }

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

        // Manual update check
        checkUpdateButton.setOnClickListener {
            statusText.text = "Checking for updates..."
            Thread {
                val release = UpdateChecker.fetchLatestRelease()
                runOnUiThread {
                    if (release == null) {
                        statusText.text = "Couldn't reach GitHub. Check your connection."
                    } else if (UpdateChecker.compareVersions(release.versionName, currentVersion()) > 0) {
                        statusText.text = "v${release.versionName} available (you're on ${currentVersion()})"
                        installOrOpen(release, statusText)
                    } else {
                        statusText.text = "You're up to date (v${currentVersion()})"
                    }
                }
            }.start()
        }
    }

    /** Current installed versionName, e.g. "1.6.23". */
    private fun currentVersion(): String {
        return try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (e: Exception) {
            "?"
        }
    }

    /**
     * When an update is available, either download + install directly (if
     * the release has an APK asset and install-from-source is allowed) or
     * open the release page.
     */
    private fun installOrOpen(release: UpdateChecker.ReleaseInfo, statusText: TextView) {
        val apkUrl = release.apkUrl
        if (apkUrl.isNullOrEmpty()) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.releaseUrl)))
            return
        }
        if (!UpdateChecker.canRequestInstalls(this)) {
            statusText.text = "Allow installs from this app to auto-update"
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.releaseUrl)))
            }
            return
        }
        statusText.text = "Downloading v${release.versionName}..."
        Thread {
            val error = UpdateChecker.downloadAndInstall(this, apkUrl)
            runOnUiThread {
                if (error != null) {
                    statusText.text = "Update failed: $error"
                } else {
                    statusText.text = "Installing v${release.versionName}..."
                }
            }
        }.start()
    }
}
