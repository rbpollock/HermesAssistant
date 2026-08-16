package com.example.hermesassistant

import android.content.Intent
import android.net.Uri
import org.json.JSONObject
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
    const val RELEASES_URL = "https://api.github.com/repos/$REPO/releases/latest"
    const val RELEASE_PAGE_URL = "https://github.com/$REPO/releases/latest"

    data class ReleaseInfo(
        val versionName: String,   // "1.6.23" (no "v" prefix)
        val apkUrl: String?,       // browser_download_url of the .apk asset, if any
        val releaseUrl: String,    // html_url of the release
        val tagName: String,       // raw tag, e.g. "v1.6.23"
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Fetch the latest release from GitHub. Returns null on any network /
     * parse failure (a check must never crash or block the caller).
     */
    fun fetchLatestRelease(): ReleaseInfo? {
        return try {
            val request = Request.Builder()
                .url(RELEASES_URL)
                .header("Accept", "application/vnd.github+json")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val json = JSONObject(response.body?.string().orEmpty())
                val tagName = json.optString("tag_name", "")
                val versionName = tagName.removePrefix("v")
                val htmlUrl = json.optString("html_url", RELEASE_PAGE_URL)
                val apkUrl = json.optJSONArray("assets")?.let { assets ->
                    for (i in 0 until assets.length()) {
                        val asset = assets.optJSONObject(i) ?: continue
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            return@let asset.optString("browser_download_url", "")
                        }
                    }
                    null
                }
                ReleaseInfo(versionName, apkUrl, htmlUrl, tagName)
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
}
