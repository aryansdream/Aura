package com.aryansdream.aura

import android.app.AlarmManager
import android.app.BroadcastOptions
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.LocalDateTime
import java.time.ZoneId

object ClassReminderScheduler {
    private const val STORE = "aura_native"
    private const val SCHEDULE_KEY = "reminder_schedule"
    private const val ACTION = "com.aryansdream.aura.CLASS_REMINDER"

    fun sync(context: Context, planner: JSONObject) {
        val prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        val previous = runCatching { JSONArray(prefs.getString(SCHEDULE_KEY, "[]")) }.getOrDefault(JSONArray())
        for (i in 0 until previous.length()) previous.optJSONObject(i)?.let { cancel(context, it.optString("id")) }
        val entries = planner.optJSONArray("schedule") ?: JSONArray()
        val saved = JSONArray()
        for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i) ?: continue
            if (!entry.optBoolean("reminderEnabled")) continue
            saved.put(JSONObject(entry.toString()))
            scheduleNext(context, entry)
        }
        prefs.edit().putString(SCHEDULE_KEY, saved.toString()).apply()
    }

    fun reschedule(context: Context, id: String) {
        val entries = runCatching { JSONArray(context.getSharedPreferences(STORE, 0).getString(SCHEDULE_KEY, "[]")) }.getOrDefault(JSONArray())
        for (i in 0 until entries.length()) entries.optJSONObject(i)?.takeIf { it.optString("id") == id }?.let { scheduleNext(context, it) }
    }

    private fun scheduleNext(context: Context, entry: JSONObject) {
        val days = entry.optJSONArray("days") ?: return
        val allowedDays = (0 until days.length()).map { days.optInt(it) }.toSet()
        val time = runCatching { LocalTime.parse(entry.optString("start")) }.getOrNull() ?: return
        val endTime = runCatching { LocalTime.parse(entry.optString("end")) }.getOrNull() ?: return
        val intervalMinutes = entry.optInt("reminderIntervalMinutes", 0).coerceAtLeast(0)
        val zone = ZoneId.systemDefault()
        val now = java.time.ZonedDateTime.now(zone)
        val trigger = (0L..7L).firstNotNullOfOrNull { offset ->
            val day = LocalDate.now(zone).plusDays(offset)
            val weekday = day.dayOfWeek.value % 7
            if (weekday !in allowedDays) return@firstNotNullOfOrNull null
            val start = day.atTime(time).atZone(zone)
            var end = day.atTime(endTime).atZone(zone)
            if (!end.isAfter(start)) end = end.plusDays(1)
            val candidate = if (now.isAfter(start) && now.isBefore(end)) {
                if (intervalMinutes == 0) null else now.plusMinutes(intervalMinutes.toLong()).takeIf { !it.isAfter(end) }
            } else start.takeIf { it.isAfter(now) }
            candidate?.toInstant()?.toEpochMilli()
        } ?: return
        val alarm = context.getSystemService(AlarmManager::class.java)
        val pending = pendingIntent(context, entry.optString("id"), entry.optString("title"))
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
    }

    private fun cancel(context: Context, id: String) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        alarm.cancel(pendingIntent(context, id, ""))
    }

    private fun pendingIntent(context: Context, id: String, title: String): PendingIntent {
        val intent = Intent(context, ClassReminderReceiver::class.java).setAction(ACTION).putExtra("id", id).putExtra("title", title)
        return PendingIntent.getBroadcast(context, id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

class ClassReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra("title").orEmpty()
        val id = intent.getStringExtra("id").orEmpty()
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) manager.createNotificationChannel(NotificationChannel(CHANNEL, "Class reminders", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Scheduled reminder")
            .setContentText(title.ifBlank { "Your scheduled activity is starting." })
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        manager.notify(id.hashCode(), notification)
        ClassReminderScheduler.reschedule(context, id)
    }

    companion object { private const val CHANNEL = "aura_class_reminders" }
}

class ClassReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences("aura_native", Context.MODE_PRIVATE)
        val data = runCatching { JSONObject(prefs.getString("planner_cache", "{}")) }.getOrDefault(JSONObject())
        ClassReminderScheduler.sync(context, data)
    }
}
