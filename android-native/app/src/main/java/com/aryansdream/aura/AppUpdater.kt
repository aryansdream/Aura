package com.aryansdream.aura

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val RELEASE_URL = "https://aura-day-tracker.vercel.app/aura-update.json"
private const val UPDATE_CHANNEL = "aura_app_updates"
private const val UPDATE_NOTIFICATION = 704

data class AuraRelease(val versionCode: Long, val versionName: String, val apkUrl: String)

object AppUpdater {
    private val lock = Any()

    fun checkAndNotify(context: Context): Boolean = synchronized(lock) {
        val release = latestRelease()
        if (release.versionCode <= installedVersionCode(context)) return false

        val prefs = context.getSharedPreferences("aura_native", Context.MODE_PRIVATE)
        if (prefs.getLong("last_notified_update_code", 0L) >= release.versionCode) return false
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            notificationManager.createNotificationChannel(
                NotificationChannel(UPDATE_CHANNEL, "AURA version updates", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val channelEnabled = Build.VERSION.SDK_INT < 26 || notificationManager.getNotificationChannel(UPDATE_CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled() || !channelEnabled) return false

        val download = PendingIntent.getActivity(
            context,
            release.versionCode.toInt(),
            Intent(Intent.ACTION_VIEW, Uri.parse(release.apkUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, UPDATE_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("AURA has a new version")
            .setContentText("Version ${release.versionName} is ready. Tap to download it.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("AURA ${release.versionName} is available. Tap to open the download in your browser, then install it when you’re ready."))
            .setContentIntent(download)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .build()
        NotificationManagerCompat.from(context).notify(UPDATE_NOTIFICATION, notification)
        prefs.edit().putLong("last_notified_update_code", release.versionCode).apply()
        true
    }

    private fun latestRelease(): AuraRelease {
        val connection = (URL(RELEASE_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            instanceFollowRedirects = true
        }
        try {
            require(connection.responseCode == HttpURLConnection.HTTP_OK) { "Could not check for AURA updates." }
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val apkUrl = json.getString("apkUrl")
            require(Uri.parse(apkUrl).scheme == "https") { "The update link is not secure." }
            return AuraRelease(json.getLong("versionCode"), json.getString("versionName"), apkUrl)
        } finally {
            connection.disconnect()
        }
    }

    private fun installedVersionCode(context: Context): Long = if (Build.VERSION.SDK_INT >= 28) {
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionCode.toLong()
    }
}
