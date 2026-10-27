package com.aryansdream.aura

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.app.usage.UsageEvents
import android.content.Context
import android.os.Build
import android.os.Process
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar

object DeviceHealth {
    val stepPermission: String = HealthPermission.getReadPermission(StepsRecord::class)

    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }

    fun todayScreenMinutes(context: Context): Int? {
        if (!hasUsageAccess(context)) return null
        val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val end = System.currentTimeMillis()
        val usage = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        // Match Android's per-app foreground totals, which underpin Digital Wellbeing's daily app usage.
        val aggregateMinutes = usage.queryAndAggregateUsageStats(start, end).values
            .sumOf { it.totalTimeInForeground }.div(60_000L).toInt()
        if (aggregateMinutes > 0) return aggregateMinutes
        val events = usage.queryEvents(start, end)
        val event = UsageEvents.Event()
        val active = mutableSetOf<String>()
        var screenInteractive = true
        var last = start
        var total = 0L
        var sawActivityEvents = false
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val time = event.timeStamp.coerceIn(start, end)
            val wasActive = active.isNotEmpty() && screenInteractive
            when (event.eventType) {
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> { screenInteractive = false; active.clear() }
                UsageEvents.Event.SCREEN_INTERACTIVE -> screenInteractive = true
                UsageEvents.Event.ACTIVITY_RESUMED -> { sawActivityEvents = true; active.add(event.packageName ?: "") }
                UsageEvents.Event.ACTIVITY_PAUSED -> { sawActivityEvents = true; active.remove(event.packageName ?: "") }
                UsageEvents.Event.MOVE_TO_FOREGROUND -> if (Build.VERSION.SDK_INT < 29) { sawActivityEvents = true; active.add(event.packageName ?: "") }
                UsageEvents.Event.MOVE_TO_BACKGROUND -> if (Build.VERSION.SDK_INT < 29) { sawActivityEvents = true; active.remove(event.packageName ?: "") }
            }
            if (wasActive) total += (time - last).coerceAtLeast(0)
            last = time
        }
        if (active.isNotEmpty() && screenInteractive) total += (end - last).coerceAtLeast(0)
        if (!sawActivityEvents || total == 0L) return aggregateMinutes
        return (total / 60_000L).toInt()
    }

    suspend fun hasStepPermission(context: Context): Boolean {
        if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) return false
        val client = HealthConnectClient.getOrCreate(context)
        return stepPermission in client.permissionController.getGrantedPermissions()
    }

    suspend fun healthSteps(context: Context): Long? {
        if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) {
            throw IllegalStateException("Health Connect is not installed or available on this device.")
        }
        val client = HealthConnectClient.getOrCreate(context)
        if (stepPermission !in client.permissionController.getGrantedPermissions()) return null
        val zone = ZoneId.systemDefault()
        val start: Instant = LocalDate.now(zone).atStartOfDay(zone).toInstant()
        val result = client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(start, Instant.now())))
        return result[StepsRecord.COUNT_TOTAL]
    }
}
