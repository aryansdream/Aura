package com.aryansdream.aura

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.media.AudioManager
import android.media.ToneGenerator
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
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(WATER_CHANNEL, "Focus water reminders", NotificationManager.IMPORTANCE_DEFAULT))
        if (Build.VERSION.SDK_INT >= 26) {
            val completion = NotificationChannel(COMPLETION_CHANNEL, "Focus session complete", NotificationManager.IMPORTANCE_DEFAULT)
            completion.description = "A silent completion notice; AURA plays a short four-beep cue."
            completion.enableVibration(false)
            completion.setSound(null, null)
            manager.createNotificationChannel(completion)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || intent?.action == ACTION_STOP_REMOTE) {
            stopFocus(syncRemote = intent.action == ACTION_STOP)
            return START_NOT_STICKY
        }
        val restoring = intent == null
        val nowWall = System.currentTimeMillis()
        val remoteSync = intent?.getBooleanExtra("remoteSync", false) ?: false
        if (remoteSync) prefs.edit()
            .putBoolean("block_social_apps", intent?.getBooleanExtra("blockApps", false) ?: false)
            .putBoolean("block_social_notifications", intent?.getBooleanExtra("blockNotifications", false) ?: false)
            .putInt("water_interval_minutes", intent?.getIntExtra("waterInterval", 30)?.coerceIn(5, 90) ?: 30)
            .apply()
        if (remoteSync) runCatching {
            val encoded = intent?.getStringExtra("blockedPackages") ?: return@runCatching
            val values = org.json.JSONArray(encoded)
            prefs.edit().putStringSet("focus_blocklist", (0 until values.length()).mapNotNull { values.optString(it).takeIf(String::isNotBlank) }.toSet()).apply()
        }
        val endWall = if (restoring) prefs.getLong("focus_wall_deadline", 0L)
            else if (remoteSync) intent?.getLongExtra("endsAt", 0L)?.takeIf { it > 0L } ?: nowWall + (intent?.getLongExtra("duration", 25L * 60_000L) ?: 25L * 60_000L)
            else nowWall + (intent?.getLongExtra("duration", 25L * 60_000L) ?: 25L * 60_000L)
        val duration = if (restoring) (endWall - nowWall).coerceAtLeast(0L) else endWall - nowWall
        if (duration <= 0L || (restoring && !prefs.getBoolean("focus_running", false))) { stopFocus(); return START_NOT_STICKY }
        val subject = intent?.getStringExtra("subject") ?: prefs.getString("focus_subject", "").orEmpty()
        val subjectId = intent?.getStringExtra("subjectId") ?: prefs.getString("focus_subject_id", "").orEmpty()
        val session = intent?.getStringExtra("session") ?: prefs.getString("focus_session", null) ?: UUID.randomUUID().toString()
        val startedWall = if (remoteSync) intent?.getLongExtra("startedAt", 0L)?.takeIf { it > 0L } ?: nowWall else nowWall
        val deadline = SystemClock.elapsedRealtime() + duration
        val waterInterval = prefs.getInt("water_interval_minutes", 30).coerceIn(5, 90)
        val edit = prefs.edit().putBoolean("focus_running", true).putLong("focus_deadline", deadline)
            .putLong("focus_wall_deadline", endWall).putString("focus_subject", subject)
            .putString("focus_subject_id", subjectId).putString("focus_session", session)
        if (!restoring) edit.putInt("focus_awarded", 0).putLong("focus_started_wall", startedWall).putLong("focus_duration_ms", endWall - startedWall)
            .putInt("water_reminder_count", 0).putLong("focus_next_water_at_elapsed", SystemClock.elapsedRealtime() + waterInterval * 60_000L)
        else if (prefs.getLong("focus_next_water_at_elapsed", 0L) == 0L) edit.putLong("focus_next_water_at_elapsed", SystemClock.elapsedRealtime() + waterInterval * 60_000L)
        edit.apply()
        prefs.edit().putString("focus_shared_session_id", session).apply()
        LanFocusSync.start(this)
        sendBroadcast(Intent(ACTION_FOCUS_STATE_CHANGED).setPackage(packageName))
        startForeground(NOTIFICATION, notification(duration, subject))
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Aura:FocusTimer").apply { setReferenceCounted(false); acquire(duration + 60_000L) }
        loop?.cancel()
        loop = CoroutineScope(Dispatchers.IO).launch {
            var awarded = prefs.getInt("focus_awarded", 0)
            var lastLanPublish = 0L
            val startedAt = prefs.getLong("focus_started_wall", nowWall)
            val totalDuration = prefs.getLong("focus_duration_ms", duration)
            while (true) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0) break
                val nowElapsed = SystemClock.elapsedRealtime()
                var nextWaterAt = prefs.getLong("focus_next_water_at_elapsed", nowElapsed + waterInterval * 60_000L)
                if (nowElapsed >= nextWaterAt) {
                    showWaterReminder()
                    nextWaterAt += waterInterval * 60_000L
                    prefs.edit().putLong("focus_next_water_at_elapsed", nextWaterAt)
                        .putInt("water_reminder_count", prefs.getInt("water_reminder_count", 0) + 1).apply()
                }
                if (nowElapsed - lastLanPublish >= 2_000L) {
                    lastLanPublish = nowElapsed
                    LanFocusSync.publish(applicationContext, session, true)
                }
                // Base awards on the original wall-clock start so service restarts don't
                // restart the two-minute counter or skip already elapsed focus time.
                val completed = ((System.currentTimeMillis() - startedAt).coerceIn(0L, totalDuration) / 120_000L).toInt()
                while (awarded < completed) {
                    awarded++
                    val pointEventId = "$session:$awarded"
                    if (LanFocusSync.hasSeenPointEvent(applicationContext, pointEventId)) {
                        prefs.edit().putInt("focus_awarded", awarded).apply()
                        continue
                    }
                    val body = JSONObject().put("action", "focus-point").put("sessionId", session).put("segment", awarded).put("subjectId", subjectId)
                    val response = runCatching { AuraApi.request(applicationContext, "/api/planner", body) }
                    if (response.isSuccess) updateCachedPoints(response.getOrNull(), optimistic = false)
                    else {
                        val error = response.exceptionOrNull()
                        val retryable = error !is AuraApi.HttpFailure || error.status == 401 || error.status == 429 || error.status >= 500
                        val accountId = runCatching { JSONObject(prefs.getString("account_cache", "{}") ?: "{}").optJSONObject("user")?.optString("id").orEmpty() }.getOrDefault("")
                        if (retryable && accountId.isNotBlank()) {
                            AuraApi.queueAction(applicationContext, "/api/planner", body, accountId)
                            updateCachedPoints(null, optimistic = true)
                        }
                    }
                    LanFocusSync.publishPoint(applicationContext, pointEventId, auraDay().toString())
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

    private fun updateCachedPoints(result: JSONObject?, optimistic: Boolean) {
        runCatching {
            val cached = JSONObject(prefs.getString("account_cache", "{}") ?: "{}")
            val stats = cached.optJSONObject("stats") ?: JSONObject()
            if (result != null) {
                stats.put("total", result.optInt("total", stats.optInt("total")))
                stats.put("completed", result.optInt("completed", stats.optInt("completed")))
                stats.put("today", result.optInt("today", stats.optInt("today")))
            } else if (optimistic) {
                stats.put("total", stats.optInt("total") + 10)
                stats.put("completed", stats.optInt("completed") + 1)
                stats.put("today", stats.optInt("today") + 1)
            }
            cached.put("stats", stats)
            val day = auraDay().toString()
            val days = cached.optJSONArray("days") ?: org.json.JSONArray()
            if ((0 until days.length()).none { days.optString(it) == day }) {
                val updatedDays = (0 until days.length()).map { days.optString(it) }.plus(day).distinct().sortedDescending()
                cached.put("days", org.json.JSONArray(updatedDays))
            }
            prefs.edit().putString("account_cache", cached.toString()).apply()
            AuraWidgetProvider.sync(this, cached)
        }
    }

    private fun showWaterReminder() {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val reminder = NotificationCompat.Builder(this, WATER_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("AURA · water break")
            .setContentText("Paani peelo, meri jaan 💧 Then continue your focus session.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(WATER_NOTIFICATION, reminder)
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
        val alert = NotificationCompat.Builder(this, COMPLETION_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Focus session complete")
            .setContentText(if (subject.isBlank()) "Your timer has finished. Take a short break." else "$subject is complete. Take a short break.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setSilent(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(COMPLETION_NOTIFICATION, alert)
        playCompletionBeeps()
    }

    private fun playCompletionBeeps() {
        Thread({
            val tone = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 75) }.getOrNull() ?: return@Thread
            try {
                tone.startTone(ToneGenerator.TONE_PROP_BEEP, 180)
                Thread.sleep(230)
                tone.startTone(ToneGenerator.TONE_PROP_BEEP, 180)
                Thread.sleep(650)
                tone.startTone(ToneGenerator.TONE_PROP_BEEP, 180)
                Thread.sleep(230)
                tone.startTone(ToneGenerator.TONE_PROP_BEEP, 180)
                Thread.sleep(200)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                tone.release()
            }
        }, "Aura-focus-completion-beep").apply { isDaemon = true; start() }
    }

    private fun stopFocus(syncRemote: Boolean = true) {
        loop?.cancel(); loop = null
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
        val session = prefs.getString("focus_session", "").orEmpty()
        if (prefs.getBoolean("focus_running", false)) saveStudySession()
        prefs.edit().putBoolean("focus_running", false).putLong("focus_deadline", 0).putLong("focus_wall_deadline", 0).putLong("focus_started_wall", 0).putLong("focus_duration_ms", 0).putInt("focus_awarded", 0).remove("focus_session").apply()
        if (syncRemote && session.isNotBlank()) LanFocusSync.publish(applicationContext, session, false)
        sendBroadcast(Intent(ACTION_FOCUS_STATE_CHANGED).setPackage(packageName))
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun saveStudySession() {
        val subjectId = prefs.getString("focus_subject_id", "").orEmpty()
        val session = prefs.getString("focus_session", "").orEmpty()
        val started = prefs.getLong("focus_started_wall", 0L)
        if (subjectId.isBlank() || session.isBlank() || started <= 0L) return
        val elapsed = (System.currentTimeMillis() - started).coerceAtLeast(0L)
            .coerceAtMost(prefs.getLong("focus_duration_ms", Long.MAX_VALUE))
        val minutes = (elapsed / 60_000L).toInt()
        if (minutes <= 0) return
        runCatching {
            val planner = JSONObject(prefs.getString("planner_cache", "{}") ?: "{}")
            val logs = planner.optJSONArray("studyLogs") ?: org.json.JSONArray()
            if ((0 until logs.length()).any { logs.optJSONObject(it)?.optString("id") == "focus_$session" }) return
            val date = java.time.Instant.ofEpochMilli(started).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
            logs.put(JSONObject().put("id", "focus_$session").put("subjectId", subjectId).put("date", date).put("minutes", minutes).put("topic", "").put("source", "focus"))
            planner.put("studyLogs", logs)
            val owner = runCatching { JSONObject(prefs.getString("account_cache", "{}") ?: "{}").optJSONObject("user")?.optString("id").orEmpty() }.getOrDefault("")
            AuraApi.savePlannerDraft(this, planner, owner)
        }
    }

    override fun onDestroy() { loop?.cancel(); if (wakeLock?.isHeld == true) wakeLock?.release(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.aryansdream.aura.STOP_FOCUS"
        const val ACTION_STOP_REMOTE = "com.aryansdream.aura.STOP_REMOTE_FOCUS"
        const val ACTION_FOCUS_STATE_CHANGED = "com.aryansdream.aura.FOCUS_STATE_CHANGED"
        private const val CHANNEL = "aura_focus"
        private const val COMPLETION_CHANNEL = "aura_focus_complete_beeps_v1"
        private const val WATER_CHANNEL = "aura_focus_water"
        private const val NOTIFICATION = 701
        private const val COMPLETION_NOTIFICATION = 702
        private const val WATER_NOTIFICATION = 703
    }
}

private object NotificationManagerCompatShim {
    fun notify(service: Service, id: Int, notification: Notification) = service.getSystemService(NotificationManager::class.java).notify(id, notification)
}
