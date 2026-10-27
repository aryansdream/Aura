package com.aryansdream.aura

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process

object FocusAppPolicy {
    private val distractingPackages = setOf(
        "com.instagram.android", "com.zhiliaoapp.musically", "com.ss.android.ugc.trill",
        "com.facebook.katana", "com.facebook.orca", "com.facebook.lite",
        "com.snapchat.android", "com.twitter.android", "com.x.android",
        "com.threads.android", "com.reddit.frontpage", "com.pinterest"
    )

    fun isDistracting(packageName: String?): Boolean = packageName != null && packageName in distractingPackages

    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }

    fun shouldEndFocus(context: Context): Boolean {
        // Without Usage Access Android has not told us which app is foregrounded;
        // preserve the timer instead of treating an unknown app as a violation.
        if (!hasUsageAccess(context)) return false
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = manager.queryEvents(now - 15_000L, now)
        val event = UsageEvents.Event()
        var packageName: String? = null
        var latest = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if ((event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND || event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) && event.timeStamp >= latest) {
                packageName = event.packageName
                latest = event.timeStamp
            }
        }
        val foreground = packageName ?: return false
        val homePackage = context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
        )?.activityInfo?.packageName
        return foreground == context.packageName || foreground == homePackage || isDistracting(foreground)
    }
}
