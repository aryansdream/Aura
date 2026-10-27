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
import java.time.format.DateTimeFormatter

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
        val oneOffDate = entry.optString("date").takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val candidateDays = if (oneOffDate != null) listOf(oneOffDate) else (0L..7L).map { LocalDate.now(zone).plusDays(it) }
        val trigger = candidateDays.firstNotNullOfOrNull { day ->
            val weekday = day.dayOfWeek.value % 7
            if (oneOffDate == null && weekday !in allowedDays) return@firstNotNullOfOrNull null
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

/** Schedules one-time task reminders with Android's system alarm service. */
object TaskReminderScheduler {
    private const val STORE = "aura_native"
    private const val KEY = "task_reminder_schedule"
    const val ACTION_NOTIFICATION = "com.aryansdream.aura.TASK_NOTIFICATION"
    const val ACTION_ALARM = "com.aryansdream.aura.TASK_ALARM"
    private val format = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

    fun sync(context: Context, planner: JSONObject) {
        val prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        val previous = runCatching { JSONArray(prefs.getString(KEY, "[]")) }.getOrDefault(JSONArray())
        for (i in 0 until previous.length()) previous.optJSONObject(i)?.let { cancel(context, it.optString("id")) }
        val tasks = planner.optJSONArray("tasks") ?: JSONArray()
        val saved = JSONArray()
        for (i in 0 until tasks.length()) {
            val task = tasks.optJSONObject(i) ?: continue
            val type = task.optString("reminderType", "none")
            if (task.optBoolean("done") || type !in setOf("notification", "alarm")) continue
            val at = runCatching { LocalDateTime.parse(task.optString("deadline"), format) }.getOrNull() ?: continue
            val trigger = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            if (trigger <= System.currentTimeMillis()) continue
            schedule(context, task.optString("id"), task.optString("title"), type, trigger)
            saved.put(JSONObject().put("id", task.optString("id")))
        }
        prefs.edit().putString(KEY, saved.toString()).apply()
    }

    private fun schedule(context: Context, id: String, title: String, type: String, trigger: Long) {
        val action = if (type == "alarm") ACTION_ALARM else ACTION_NOTIFICATION
        val intent = Intent(context, TaskReminderReceiver::class.java).setAction(action)
            .putExtra("id", id).putExtra("title", title).putExtra("reminderType", type)
        val broadcast = PendingIntent.getBroadcast(context, id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        if (type == "alarm") {
            val open = PendingIntent.getActivity(context, id.hashCode(), Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(trigger, open), broadcast)
        } else {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, broadcast)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, broadcast)
            }
        }
    }

    private fun cancel(context: Context, id: String) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        for (action in listOf(ACTION_NOTIFICATION, ACTION_ALARM)) {
            val intent = Intent(context, TaskReminderReceiver::class.java).setAction(action)
            val pending = PendingIntent.getBroadcast(context, id.hashCode(), intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
            if (pending != null) { alarmManager.cancel(pending); pending.cancel() }
        }
    }
}

class TaskReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra("title").orEmpty().ifBlank { "Your scheduled task" }
        val type = intent.getStringExtra("reminderType") ?: if (intent.action == TaskReminderScheduler.ACTION_ALARM) "alarm" else "notification"
        val manager = context.getSystemService(NotificationManager::class.java)
        val channelId = if (type == "alarm") ALARM_CHANNEL else NOTIFICATION_CHANNEL
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(channelId) == null) {
            val channel = NotificationChannel(channelId, if (type == "alarm") "Task alarms" else "Task notifications", if (type == "alarm") NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT)
            channel.description = if (type == "alarm") "Sounding reminders for your scheduled tasks" else "Reminders for scheduled tasks"
            if (type == "alarm") channel.enableVibration(true)
            manager.createNotificationChannel(channel)
        }
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(if (type == "alarm") "AURA task alarm" else "AURA task reminder")
            .setContentText(title)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(if (type == "alarm") NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_DEFAULT)
            .setDefaults(if (type == "alarm") NotificationCompat.DEFAULT_ALL else NotificationCompat.DEFAULT_SOUND)
            .build()
        manager.notify(intent.getStringExtra("id").orEmpty().hashCode(), notification)
    }

    companion object {
        private const val NOTIFICATION_CHANNEL = "aura_task_notifications"
        private const val ALARM_CHANNEL = "aura_task_alarms"
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
            context.getSystemService(NotificationManager::class.java).cancel(id.hashCode() + date.hashCode())
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Class reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Beep when a class starts and remind you after class"
                enableVibration(true)
            })
        }
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(if (intent.action == ClassReminderScheduler.ACTION_CLASS_END) "Class finished · did you attend?" else "Scheduled reminder")
            .setContentText(if (intent.action == ClassReminderScheduler.ACTION_CLASS_END) title else title.ifBlank { "Your scheduled activity is starting." })
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        if (intent.action != ClassReminderScheduler.ACTION_CLASS_END) {
            builder.setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)
        }
        if (intent.action == ClassReminderScheduler.ACTION_CLASS_END) {
            val yes = PendingIntent.getBroadcast(context, id.hashCode() * 31 + 2, Intent(context, ClassReminderReceiver::class.java).setAction(ClassReminderScheduler.ACTION_MARK_CLASS).putExtra("id", id).putExtra("title", title).putExtra("date", date), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val no = PendingIntent.getBroadcast(context, id.hashCode() * 31 + 3, Intent(context, ClassReminderReceiver::class.java).setAction(ClassReminderScheduler.ACTION_SKIP_CLASS).putExtra("id", id).putExtra("title", title).putExtra("date", date), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(0, "Attended", yes).addAction(0, "Not completed", no)
        }
        manager.notify(id.hashCode() + date.hashCode(), builder.build())
        ClassReminderScheduler.reschedule(context, id)
    }

    companion object { private const val CHANNEL = "aura_class_reminders" }
}

class ClassReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val prefs = context.getSharedPreferences("aura_native", Context.MODE_PRIVATE)
        val data = runCatching { JSONObject(prefs.getString("planner_cache", "{}")) }.getOrDefault(JSONObject())
        ClassReminderScheduler.sync(context, data)
        TaskReminderScheduler.sync(context, data)
    }
}
