package com.aryansdream.aura

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.media.AudioAttributes
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

class FocusService : Service() {
    private var loop: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val prefs by lazy { getSharedPreferences("aura_native", Context.MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL, "Focus timer", NotificationManager.IMPORTANCE_LOW))
        if (Build.VERSION.SDK_INT >= 26) {
            val alarm = NotificationChannel(ALARM_CHANNEL, "Focus timer alerts", NotificationManager.IMPORTANCE_HIGH)
            alarm.description = "Sound and notification when a focus session ends"
            alarm.enableVibration(true)
            alarm.vibrationPattern = longArrayOf(0, 450, 180, 450)
            alarm.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            manager.createNotificationChannel(alarm)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopFocus(); return START_NOT_STICKY }
        val restoring = intent == null
        val nowWall = System.currentTimeMillis()
        val endWall = if (restoring) prefs.getLong("focus_wall_deadline", 0L) else nowWall + (intent?.getLongExtra("duration", 25L * 60_000L) ?: 25L * 60_000L)
        val duration = if (restoring) (endWall - nowWall).coerceAtLeast(0L) else endWall - nowWall
        if (duration <= 0L || (restoring && !prefs.getBoolean("focus_running", false))) { stopFocus(); return START_NOT_STICKY }
        val subject = intent?.getStringExtra("subject") ?: prefs.getString("focus_subject", "").orEmpty()
        val subjectId = intent?.getStringExtra("subjectId") ?: prefs.getString("focus_subject_id", "").orEmpty()
        val session = intent?.getStringExtra("session") ?: prefs.getString("focus_session", null) ?: UUID.randomUUID().toString()
        val deadline = SystemClock.elapsedRealtime() + duration
        val edit = prefs.edit().putBoolean("focus_running", true).putLong("focus_deadline", deadline)
            .putLong("focus_wall_deadline", endWall).putString("focus_subject", subject)
            .putString("focus_subject_id", subjectId).putString("focus_session", session)
        if (!restoring) edit.putInt("focus_awarded", 0)
        edit.apply()
        startForeground(NOTIFICATION, notification(duration, subject))
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Aura:FocusTimer").apply { setReferenceCounted(false); acquire(duration + 60_000L) }
        loop?.cancel()
        loop = CoroutineScope(Dispatchers.IO).launch {
            var awarded = prefs.getInt("focus_awarded", 0)
            while (true) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0) break
                val completed = ((duration - remaining).coerceAtLeast(0) / 120_000L).toInt()
                while (awarded < completed) {
                    awarded++
                    val body = JSONObject().put("action", "focus-point").put("sessionId", session).put("segment", awarded).put("subjectId", subjectId)
                    runCatching { AuraApi.request(applicationContext, "/api/planner", body) }
                        .onFailure {
                            val accountId = runCatching { JSONObject(prefs.getString("account_cache", "{}") ?: "{}").optJSONObject("user")?.optString("id").orEmpty() }.getOrDefault("")
                            if (accountId.isNotBlank()) AuraApi.queueAction(applicationContext, "/api/planner", body, accountId)
                        }
                    prefs.edit().putInt("focus_awarded", awarded).apply()
                }
                NotificationManagerCompatShim.notify(this@FocusService, NOTIFICATION, notification(remaining, subject))
                delay(1000)
            }
            showCompletion(subject)
            stopFocus()
        }
        return START_STICKY
    }

    private fun notification(remainingMs: Long, subject: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, FocusService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val seconds = (remainingMs.coerceAtLeast(0) / 1000).toInt()
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle("AURA focus · ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}")
            .setContentText(subject.ifBlank { "Stay with your study session" }).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(0, "End focus", stop).build()
    }

    private fun showCompletion(subject: String) {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val alert = NotificationCompat.Builder(this, ALARM_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Focus session complete")
            .setContentText(if (subject.isBlank()) "Your timer has finished. Take a short break." else "$subject is complete. Take a short break.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVibrate(longArrayOf(0, 450, 180, 450))
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
            .build()
        getSystemService(NotificationManager::class.java).notify(ALARM_NOTIFICATION, alert)
    }

    private fun stopFocus() {
        loop?.cancel(); loop = null
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
        prefs.edit().putBoolean("focus_running", false).putLong("focus_deadline", 0).putLong("focus_wall_deadline", 0).putInt("focus_awarded", 0).remove("focus_session").apply()
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { loop?.cancel(); if (wakeLock?.isHeld == true) wakeLock?.release(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.aryansdream.aura.STOP_FOCUS"
        private const val CHANNEL = "aura_focus"
        private const val ALARM_CHANNEL = "aura_focus_alarm"
        private const val NOTIFICATION = 701
        private const val ALARM_NOTIFICATION = 702
    }
}

private object NotificationManagerCompatShim {
    fun notify(service: Service, id: Int, notification: Notification) = service.getSystemService(NotificationManager::class.java).notify(id, notification)
}
