package com.example.hermesassistant

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Checks the GitHub releases API for a newer HermesAssistant release.
 *
 * Pure helper (no service/receiver): the caller (MainActivity / Settings)
 * decides when to check and how to surface the result. The GitHub API for a
 * public repo needs no auth.
 */
object UpdateChecker {

    const val REPO = "rbpollock/HermesAssistant"
    const val ACTION_UPDATE = "com.example.hermesassistant.ACTION_UPDATE"
    // Atom feed, NOT the REST API: GitHub's api.github.com allows only 60
    // unauthenticated requests/hour per IP, and phones behind carrier NAT
    // share an IP with many users — the budget is routinely exhausted so
    // update checks silently fail. The atom feed has no such rate limit.
    const val RELEASES_FEED_URL = "https://github.com/$REPO/releases.atom"
    const val RELEASE_PAGE_URL = "https://github.com/$REPO/releases/latest"
    // Releases follow a deterministic naming convention, so the APK asset
    // URL can be constructed from the tag without querying the API:
    //   /releases/download/vX.Y.Z/HermesAssistant-vX.Y.Z.apk
    fun apkUrlForTag(tagName: String): String =
        "https://github.com/$REPO/releases/download/$tagName/HermesAssistant-$tagName.apk"

    data class ReleaseInfo(
        val versionName: String,   // "1.6.25" (no "v" prefix)
        val apkUrl: String?,       // constructed download URL for the .apk
        val releaseUrl: String = "https://github.com/$REPO/releases/latest",
        val tagName: String = "",
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Fetch the latest release from the GitHub atom feed (no REST API rate
     * limit). Returns null on any network / parse failure.
     */
    fun fetchLatestRelease(): ReleaseInfo? {
        return try {
            val request = Request.Builder()
                .url(RELEASES_FEED_URL)
                .header("Accept", "application/atom+xml, text/xml, */*")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string().orEmpty()

                // First <entry> holds the newest release; its <link> points
                // at the release page, e.g. .../releases/tag/v1.6.25
                val tagMatch = Regex("""releases/tag/([^"/]+)""").find(body)
                    ?: return null
                val tagName = tagMatch.groupValues[1]
                val versionName = tagName.removePrefix("v")
                val releaseUrl = "https://github.com/$REPO/releases/tag/$tagName"
                // Assume the deterministic APK name exists for this tag.
                val apkUrl = apkUrlForTag(tagName)
                ReleaseInfo(versionName, apkUrl, releaseUrl, tagName)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Semantic-ish version comparison. Returns >0 when a is newer than b,
     * <0 when older, 0 when equal. Handles the "1.9.0 > 1.6.23" case that a
     * plain string compare gets wrong.
     */
    fun compareVersions(a: String, b: String): Int {
        fun parts(v: String): List<Int> =
            v.trim().removePrefix("v").split(".")
                .map { it.toIntOrNull() ?: 0 }

        val pa = parts(a)
        val pb = parts(b)
        val maxLen = maxOf(pa.size, pb.size)
        for (i in 0 until maxLen) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return 0
    }

    /**
     * Download the given APK URL into filesDir/apk-updates/ and hand it to
     * the system installer via FileProvider + ACTION_VIEW.
     *
     * Returns an error message on failure, or null on success (the system
     * install dialog takes over). Caller must check
     * canRequestPackageInstalls() first (Android 8+ unknown-sources gate).
     */
    fun downloadAndInstall(context: android.content.Context, apkUrl: String): String? {
        return try {
            val dir = context.getExternalFilesDir(null)
                ?: context.filesDir
            val apkDir = File(dir, "apk-updates").apply { mkdirs() }
            val fileName = apkUrl.substringAfterLast('/').ifBlank { "update.apk" }
            val target = File(apkDir, fileName)
            // Delete any stale download so the FileProvider URI points at the
            // fresh bytes.
            if (target.exists()) target.delete()

            val request = Request.Builder().url(apkUrl).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return "Download failed (HTTP ${response.code})"
                response.body?.byteStream()?.use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output) }
                }
            }

            if (!target.exists() || target.length() == 0L) return "Download failed (empty file)"

            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                target
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
            null // success — the system installer is showing
        } catch (e: Exception) {
            "Install failed: ${e.message}"
        }
    }

    /** True when the app may install APKs from this source (Android 8+). */
    fun canRequestInstalls(context: android.content.Context): Boolean {
        return try {
            context.packageManager.canRequestPackageInstalls()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Download the APK for the given release and hand it to the system
     * installer. Returns a human-readable status: "installing..." on
     * success (the system dialog takes over), or an error message.
     */
    fun installRelease(context: android.content.Context, release: ReleaseInfo): String {
        val apkUrl = release.apkUrl
        if (apkUrl.isNullOrEmpty()) {
            // No APK asset — open the release page
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.releaseUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (e: Exception) {
                return "No browser available"
            }
            return "Opened release page"
        }
        if (!canRequestInstalls(context)) {
            // Android 8+: installing from "unknown sources" needs a one-time
            // per-source grant. Take the user to that settings screen.
            try {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            } catch (e: Exception) {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.releaseUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
            return "Allow installs from this app, then tap INSTALL UPDATE"
        }
        val error = downloadAndInstall(context, apkUrl)
        return if (error != null) "Update failed: $error" else "Installing v${release.versionName}..."
    }

    // ------------------------------------------------------------------
    // One-call background check used by MainActivity (onResume) and the
    // foreground service (onCreate/periodic): fetch latest release and,
    // if newer than the installed version, post the update notification.
    // Throttled so the API isn't hammered.
    // ------------------------------------------------------------------
    private const val CHECK_THROTTLE_MS = 60 * 60 * 1000L // 1h
    private const val PREFS = "updates"
    private const val KEY_LAST_CHECK = "last_check_ms"
    private const val UPDATE_CHANNEL = "hermes_updates"
    private const val UPDATE_NOTIF_ID = 31000

    /** Installed versionName, e.g. "1.6.25". */
    fun installedVersion(context: android.content.Context): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (e: Exception) {
            "?"
        }
    }

    /**
     * Throttled check. Returns true when a check actually ran; false when
     * it was skipped (throttle window, no network, nothing newer). Safe to
     * call from any thread — the network happens on a background thread and
     * the notification is posted from that thread too.
     */
    fun checkAndNotify(context: android.content.Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_CHECK, 0L) < CHECK_THROTTLE_MS) return false
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()

        Thread {
            val release = fetchLatestRelease() ?: return@Thread
            if (compareVersions(release.versionName, installedVersion(context)) > 0) {
                postUpdateNotification(context, release)
            }
        }.start()
        return true
    }

    /** Force a check regardless of throttle (Settings button). Returns null on failure. */
    fun checkNow(context: android.content.Context): String? {
        return try {
            val release = fetchLatestRelease()
            if (release == null) {
                "Couldn't reach GitHub. Check your connection."
            } else if (compareVersions(release.versionName, installedVersion(context)) > 0) {
                postUpdateNotification(context, release)
                "v${release.versionName} available (you're on ${installedVersion(context)})"
            } else {
                "You're up to date (v${installedVersion(context)})"
            }
        } catch (e: Exception) {
            "Update check failed: ${e.message}"
        }
    }

    private fun postUpdateNotification(context: android.content.Context, release: ReleaseInfo) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                return // notifications blocked — nothing to show
            }
            val manager = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            manager.createNotificationChannel(
                android.app.NotificationChannel(UPDATE_CHANNEL, "Hermes Updates", android.app.NotificationManager.IMPORTANCE_DEFAULT)
            )

            // Tap = open the release page; Update action = download + install
            val openIntent = Intent(Intent.ACTION_VIEW, Uri.parse(release.releaseUrl))
            val openPi = android.app.PendingIntent.getActivity(
                context, UPDATE_NOTIF_ID, openIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val updateIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                action = ACTION_UPDATE
                putExtra("update_apk_url", release.apkUrl)
                putExtra("update_version", release.versionName)
            }
            val updatePi = android.app.PendingIntent.getActivity(
                context, UPDATE_NOTIF_ID + 1, updateIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val updateAction = androidx.core.app.NotificationCompat.Action.Builder(
                android.R.drawable.ic_menu_save,
                "Update",
                updatePi
            ).build()

            val cur = installedVersion(context)
            val builder = androidx.core.app.NotificationCompat.Builder(context, UPDATE_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Hermes Assistant v${release.versionName} available")
                .setContentText("You're on $cur. Tap Update to download and install.")
                .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText("You're on $cur. Tap Update to download and install the new release."))
                .setAutoCancel(true)
                .setContentIntent(openPi)
                .addAction(updateAction)
            manager.notify(UPDATE_NOTIF_ID, builder.build())
        } catch (e: Exception) {
            // Update notification is best-effort
        }
    }
}
