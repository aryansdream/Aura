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
    const val ACTION_CLASS_END = "com.aryansdream.aura.CLASS_END"
    const val ACTION_MARK_CLASS = "com.aryansdream.aura.MARK_CLASS_ATTENDED"
    const val ACTION_SKIP_CLASS = "com.aryansdream.aura.SKIP_CLASS_ATTENDANCE"

    fun sync(context: Context, planner: JSONObject) {
        val prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        val previous = runCatching { JSONArray(prefs.getString(SCHEDULE_KEY, "[]")) }.getOrDefault(JSONArray())
        for (i in 0 until previous.length()) previous.optJSONObject(i)?.let { cancel(context, it.optString("id")) }
        val entries = planner.optJSONArray("schedule") ?: JSONArray()
        val saved = JSONArray()
        for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i) ?: continue
            val isClass = entry.optString("kind") == "Class"
            if (!entry.optBoolean("reminderEnabled") && !isClass) continue
            saved.put(JSONObject(entry.toString()))
            if (entry.optBoolean("reminderEnabled")) scheduleNext(context, entry)
            if (isClass) scheduleClassEnd(context, entry)
        }
        prefs.edit().putString(SCHEDULE_KEY, saved.toString()).apply()
    }

    fun reschedule(context: Context, id: String) {
        val entries = runCatching { JSONArray(context.getSharedPreferences(STORE, 0).getString(SCHEDULE_KEY, "[]")) }.getOrDefault(JSONArray())
        for (i in 0 until entries.length()) entries.optJSONObject(i)?.takeIf { it.optString("id") == id }?.let {
            if (it.optBoolean("reminderEnabled")) scheduleNext(context, it)
            if (it.optString("kind") == "Class") scheduleClassEnd(context, it)
        }
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
        alarm.cancel(endIntent(context, id, "", ""))
    }

    private fun pendingIntent(context: Context, id: String, title: String): PendingIntent {
        val intent = Intent(context, ClassReminderReceiver::class.java).setAction(ACTION).putExtra("id", id).putExtra("title", title)
        return PendingIntent.getBroadcast(context, id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun endIntent(context: Context, id: String, title: String, date: String): PendingIntent {
        val intent = Intent(context, ClassReminderReceiver::class.java).setAction(ACTION_CLASS_END)
            .putExtra("id", id).putExtra("title", title).putExtra("date", date)
        return PendingIntent.getBroadcast(context, id.hashCode() * 31 + 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun scheduleClassEnd(context: Context, entry: JSONObject) {
        val zone = ZoneId.systemDefault()
        val now = java.time.ZonedDateTime.now(zone)
        val days = entry.optJSONArray("days") ?: JSONArray()
        val allowedDays = (0 until days.length()).map { days.optInt(it) }.toSet()
        val endTime = runCatching { LocalTime.parse(entry.optString("end")) }.getOrNull() ?: return
        val oneOffDate = entry.optString("date").takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }
        val day = if (oneOffDate != null) runCatching { LocalDate.parse(oneOffDate) }.getOrNull()?.takeIf { !it.atTime(endTime).atZone(zone).isBefore(now) }
            else (0L..7L).map { LocalDate.now(zone).plusDays(it) }.firstOrNull { candidate ->
                candidate.dayOfWeek.value % 7 in allowedDays && candidate.atTime(endTime).atZone(zone).isAfter(now)
            }
        val trigger = day?.atTime(endTime)?.atZone(zone)?.toInstant()?.toEpochMilli() ?: return
        val pending = endIntent(context, entry.optString("id"), entry.optString("title"), day.toString())
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
    }
}

class ClassReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra("title").orEmpty()
        val id = intent.getStringExtra("id").orEmpty()
        val date = intent.getStringExtra("date").orEmpty()
        if (intent.action == ClassReminderScheduler.ACTION_MARK_CLASS || intent.action == ClassReminderScheduler.ACTION_SKIP_CLASS) {
            val prefs = context.getSharedPreferences("aura_native", Context.MODE_PRIVATE)
            prefs.edit().putString("class_result_${id}_$date", if (intent.action == ClassReminderScheduler.ACTION_MARK_CLASS) "attended" else "not_attended").apply()
            if (intent.action == ClassReminderScheduler.ACTION_MARK_CLASS) {
                val pending = goAsync()
                Thread {
                    try {
                        val body = JSONObject().put("action", "class-attendance").put("activityId", id).put("title", title).put("date", date)
                        runCatching { AuraApi.request(context, "/api/planner", body) }.onFailure {
                            val accountId = runCatching { JSONObject(prefs.getString("account_cache", "{}") ?: "{}").optJSONObject("user")?.optString("id").orEmpty() }.getOrDefault("")
                            if (accountId.isNotBlank()) AuraApi.queueAction(context, "/api/planner", body, accountId)
                        }
                    } finally { pending.finish() }
                }.start()
            }
            context.getSystemService(NotificationManager::class.java).cancel(id.hashCode() + date.hashCode())
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) manager.createNotificationChannel(NotificationChannel(CHANNEL, "Class reminders", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(if (intent.action == ClassReminderScheduler.ACTION_CLASS_END) "Class finished · did you attend?" else "Scheduled reminder")
            .setContentText(if (intent.action == ClassReminderScheduler.ACTION_CLASS_END) title else title.ifBlank { "Your scheduled activity is starting." })
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        if (intent.action == ClassReminderScheduler.ACTION_CLASS_END) {
            val yes = PendingIntent.getBroadcast(context, id.hashCode() * 31 + 2, Intent(context, ClassReminderReceiver::class.java).setAction(ClassReminderScheduler.ACTION_MARK_CLASS).putExtra("id", id).putExtra("title", title).putExtra("date", date), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val no = PendingIntent.getBroadcast(context, id.hashCode() * 31 + 3, Intent(context, ClassReminderReceiver::class.java).setAction(ClassReminderScheduler.ACTION_SKIP_CLASS).putExtra("id", id).putExtra("title", title).putExtra("date", date), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(0, "Attended · +0.10 AP", yes).addAction(0, "Not attended", no)
        }
        manager.notify(id.hashCode() + date.hashCode(), builder.build())
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
