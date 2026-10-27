package com.aryansdream.aura

import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.provider.Settings
import android.media.session.MediaSessionManager
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper

class FocusNotificationListener : NotificationListenerService() {
    private val mediaHandler = Handler(Looper.getMainLooper())
    private val lastMediaSignatures = mutableSetOf<String>()
    private val lastMediaSentAt = mutableMapOf<String, Long>()
    private val lastLocalPauseAt = mutableMapOf<String, Long>()
    private val mediaPoll = object : Runnable {
        override fun run() {
            scanActiveMedia()
            mediaHandler.postDelayed(this, 10_000L)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        if (isBlockerEnabled() && isFocusRunning()) activeNotifications.orEmpty()
            .filter { FocusAppPolicy.isDistracting(this, it.packageName) }
            .forEach { runCatching { cancelNotification(it.key) } }
        mediaHandler.removeCallbacks(mediaPoll)
        mediaHandler.post(mediaPoll)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn != null && isBlockerEnabled() && isFocusRunning() && FocusAppPolicy.isDistracting(this, sbn.packageName)) {
            runCatching { cancelNotification(sbn.key) }
        }
        if (sbn != null && sbn.notification?.category == android.app.Notification.CATEGORY_TRANSPORT) scanActiveMedia()
    }

    override fun onListenerDisconnected() {
        mediaHandler.removeCallbacks(mediaPoll)
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        mediaHandler.removeCallbacks(mediaPoll)
        super.onDestroy()
    }

    private fun scanActiveMedia() {
        val prefs = getSharedPreferences("aura_native", MODE_PRIVATE)
        if (!prefs.getBoolean("media_detection_enabled", false) || !prefs.getBoolean("focus_running", false)) {
            lastMediaSignatures.clear(); lastMediaSentAt.clear(); return
        }
        if (!isEnabled(this)) return
        runCatching {
            val manager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
            val component = ComponentName(this, FocusNotificationListener::class.java)
            val controllers = manager.getActiveSessions(component)
            val playing = controllers.filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            val currentSignatures = mutableSetOf<String>()
            playing.forEach { active ->
                val metadata = active.metadata
                val title = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE).orEmpty()
                val artist = metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
                if (title.isBlank() && artist.isBlank()) return@forEach
                val packageName = active.packageName
                val signature = "$packageName|$title|$artist"
                currentSignatures.add(signature)
                val now = System.currentTimeMillis()
                if (prefs.getBoolean("media_auto_pause_enabled", false)) {
                    val result = LocalMediaClassifier.classify("$packageName $title $artist")
                    if (result.category == "entertainment" && result.confidence >= 0.76 &&
                        now - (lastLocalPauseAt[signature] ?: 0L) >= 10_000L) {
                        lastLocalPauseAt[signature] = now
                        prefs.edit().putString("media_blocked_package", packageName).apply()
                        runCatching { active.transportControls.pause() }
                        sendBroadcast(android.content.Intent(FocusBlockAccessibilityService.ACTION_MEDIA_RESTRICTED).setPackage(this@FocusNotificationListener.packageName))
                    }
                }
                if (signature !in lastMediaSignatures || now - (lastMediaSentAt[signature] ?: 0L) >= 30_000L) {
                    val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString() }
                        .getOrDefault(packageName.substringAfterLast('.'))
                    LanFocusSync.publishMedia(this, label, packageName, title, artist)
                    lastMediaSentAt[signature] = now
                }
            }
            lastMediaSignatures.clear()
            lastMediaSignatures.addAll(currentSignatures)
            lastMediaSentAt.keys.retainAll(currentSignatures)
        }
    }

    private fun isFocusRunning() = getSharedPreferences("aura_native", MODE_PRIVATE).getBoolean("focus_running", false)
    private fun isBlockerEnabled() = getSharedPreferences("aura_native", MODE_PRIVATE).getBoolean("block_social_notifications", false)

    companion object {
        fun pauseMedia(context: Context, packageName: String) {
            if (packageName.isBlank() || !isEnabled(context)) return
            runCatching {
                val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
                val component = ComponentName(context, FocusNotificationListener::class.java)
                manager.getActiveSessions(component).filter { it.packageName == packageName }
                    .forEach { it.transportControls.pause() }
            }
        }

        fun isEnabled(context: Context): Boolean {
            val component = ComponentName(context, FocusNotificationListener::class.java)
            val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
            return enabled.split(':').mapNotNull(ComponentName::unflattenFromString).any { it == component }
        }
    }
}
