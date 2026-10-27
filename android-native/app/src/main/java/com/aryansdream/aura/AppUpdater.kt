package com.aryansdream.aura

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

private const val RELEASE_URL = "https://aura-day-tracker.vercel.app/aura-update.json"
private const val MAX_APK_BYTES = 200L * 1024L * 1024L

data class AuraRelease(val versionCode: Long, val versionName: String, val apkUrl: String, val sha256: String)

object AppUpdater {
    fun latestRelease(): AuraRelease {
        val json = JSONObject(readHttps(RELEASE_URL, 256 * 1024))
        val apkUrl = json.getString("apkUrl")
        require(Uri.parse(apkUrl).scheme == "https") { "The update link is not secure." }
        val hash = json.getString("sha256").lowercase()
        require(hash.matches(Regex("[a-f0-9]{64}"))) { "The update file could not be verified." }
        return AuraRelease(json.getLong("versionCode"), json.getString("versionName"), apkUrl, hash)
    }

    fun installedVersionCode(context: Context): Long = if (Build.VERSION.SDK_INT >= 28) {
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionCode.toLong()
    }

    fun download(context: Context, release: AuraRelease): File {
        val file = File(context.cacheDir, "updates/aura-${release.versionCode}.apk")
        file.parentFile?.mkdirs()
        file.delete()
        val connection = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            requestMethod = "GET"
        }
        try {
            require(connection.responseCode == HttpURLConnection.HTTP_OK) { "Could not download the update." }
            require(connection.contentLengthLong == -1L || connection.contentLengthLong in 0..MAX_APK_BYTES) { "The update file is too large." }
            connection.inputStream.use { input -> file.outputStream().use { output ->
                val buffer = ByteArray(32 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= MAX_APK_BYTES) { "The update file is too large." }
                    output.write(buffer, 0, read)
                }
            } }
        } finally {
            connection.disconnect()
        }
        val actualHash = file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        require(actualHash == release.sha256) { file.delete(); "The downloaded update failed its security check." }
        return file
    }

    fun installerIntent(context: Context, apk: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun readHttps(address: String, maxBytes: Int): String {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            instanceFollowRedirects = true
        }
        try {
            require(connection.responseCode == HttpURLConnection.HTTP_OK) { "Could not check for updates. Try again later." }
            require(connection.contentLengthLong == -1L || connection.contentLengthLong in 0..maxBytes.toLong()) { "Invalid update information." }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
