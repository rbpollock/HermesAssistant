package com.example.hermesassistant.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.hermesassistant.AppSettings
import com.example.hermesassistant.ServerConfig
import com.example.hermesassistant.UpdateChecker
import com.example.hermesassistant.ui.theme.HermesTheme

/**
 * Phase-4 Compose settings surface: server host/port, audio routing
 * (BT-only + mute), version + GitHub, and manual update check/install.
 * Replaces the legacy SettingsActivity Views for the sheet's gear entry.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onServerChanged: () -> Unit,
) {
    HermesTheme {
        val context = LocalContext.current
        var host by remember { mutableStateOf(ServerConfig.host(context)) }
        var port by remember { mutableStateOf(ServerConfig.port(context)) }
        var btOnly by remember { mutableStateOf(AppSettings.playOverBluetoothOnly(context)) }
        var mute by remember { mutableStateOf(AppSettings.muteVoice(context)) }
        var status by remember { mutableStateOf("") }
        var updateReady by remember { mutableStateOf<UpdateChecker.ReleaseInfo?>(null) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(
                    text = "Settings",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            SectionLabel("Home server")
            Caption("IP or hostname of the Hermes relay (e.g. 100.123.127.108)")
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Host") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = fieldColors(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = port,
                onValueChange = { port = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Port") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = fieldColors(),
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    val h = host.trim()
                    val p = port.trim()
                    if (h.isEmpty() || p.isEmpty()) {
                        status = "Host and port are required."
                        return@Button
                    }
                    ServerConfig.save(context, h, p)
                    status = "Saved: ${ServerConfig.httpBase(context)}"
                    onServerChanged() // reconnect against the new target
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
            ) {
                Text("SAVE", fontWeight = FontWeight.Bold)
            }

            SectionLabel("Audio")
            SettingsRow(
                title = "Play over Bluetooth only",
                subtitle = "Off: responses play over the phone speaker",
                checked = btOnly,
                onCheckedChange = {
                    btOnly = it
                    AppSettings.setPlayOverBluetoothOnly(context, it)
                },
            )
            SettingsRow(
                title = "Mute voice",
                subtitle = "Notifications still appear, but Hermes stays silent",
                checked = mute,
                onCheckedChange = {
                    mute = it
                    AppSettings.setMuteVoice(context, it)
                },
            )

            SectionLabel("About")
            Caption("Version ${currentVersion(context)}")
            Text(
                text = "View on GitHub",
                color = MaterialTheme.colorScheme.secondary,
                fontSize = 14.sp,
                modifier = Modifier
                    .padding(top = 4.dp, bottom = 16.dp)
                    .clickable {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.RELEASE_PAGE_URL)))
                        } catch (e: Exception) {
                            status = "No browser available"
                        }
                    },
            )

            Button(
                onClick = {
                    status = "Checking for updates..."
                    updateReady = null
                    val main = android.os.Handler(android.os.Looper.getMainLooper())
                    Thread {
                        val release = UpdateChecker.fetchLatestRelease()
                        val result = if (release == null) {
                            "Couldn't reach GitHub. Check your connection."
                        } else if (UpdateChecker.compareVersions(release.versionName, currentVersion(context)) > 0) {
                            main.post { updateReady = release }
                            "v${release.versionName} available (you're on ${currentVersion(context)})"
                        } else {
                            "You're up to date (v${currentVersion(context)})"
                        }
                        main.post { status = result }
                    }.start()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.secondary,
                ),
            ) {
                Text("CHECK FOR UPDATES", fontWeight = FontWeight.Bold)
            }

            if (updateReady != null) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        status = "Downloading v${updateReady!!.versionName}..."
                        val release = updateReady!!
                        val main = android.os.Handler(android.os.Looper.getMainLooper())
                        Thread {
                            val message = UpdateChecker.installRelease(context, release)
                            main.post { status = message }
                        }.start()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Text("INSTALL UPDATE", fontWeight = FontWeight.Bold)
                }
            }

            if (status.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = status,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 24.dp, bottom = 6.dp),
    )
}

@Composable
private fun Caption(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.sp,
        modifier = Modifier.padding(bottom = 12.dp),
    )
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
            )
        }
    }
}

@Composable
private fun fieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
    cursorColor = MaterialTheme.colorScheme.primary,
)

private fun currentVersion(context: Context): String {
    return try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }
}
