package com.aryansdream.aura

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
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
        return usage.queryAndAggregateUsageStats(start, end).values.sumOf { it.totalTimeInForeground }.div(60_000L).toInt()
    }

    suspend fun healthSteps(context: Context): Long? {
        val client = HealthConnectClient.getOrCreate(context)
        if (stepPermission !in client.permissionController.getGrantedPermissions()) return null
        val zone = ZoneId.systemDefault()
        val start: Instant = LocalDate.now(zone).atStartOfDay(zone).toInstant()
        val result = client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(start, Instant.now())))
        return result[StepsRecord.COUNT_TOTAL]
    }
}
