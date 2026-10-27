package com.aryansdream.aura

import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.provider.Settings

class FocusNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        if (isFocusRunning()) activeNotifications.orEmpty()
            .filter { FocusAppPolicy.isDistracting(it.packageName) }
            .forEach { runCatching { cancelNotification(it.key) } }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn != null && isFocusRunning() && FocusAppPolicy.isDistracting(sbn.packageName)) {
            runCatching { cancelNotification(sbn.key) }
        }
    }

    private fun isFocusRunning() = getSharedPreferences("aura_native", MODE_PRIVATE).getBoolean("focus_running", false)

    companion object {
        fun isEnabled(context: Context): Boolean {
            val component = ComponentName(context, FocusNotificationListener::class.java)
            val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
            return enabled.split(':').mapNotNull(ComponentName::unflattenFromString).any { it == component }
        }
    }
}
