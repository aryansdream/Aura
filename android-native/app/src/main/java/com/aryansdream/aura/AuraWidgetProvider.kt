package com.aryansdream.aura

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import org.json.JSONObject
import java.time.LocalDate

class AuraWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { updateWidget(context, manager, it) }
    }

    companion object {
        private const val PREFS = "aura_native"

        fun sync(context: Context, account: JSONObject) {
            val stats = account.optJSONObject("stats") ?: JSONObject()
            val earnedDays = account.optJSONArray("days")
            val earned = (0 until (earnedDays?.length() ?: 0)).mapNotNull { earnedDays?.optString(it) }.toSet()
            var streak = 0
            var date = LocalDate.now()
            while (earned.contains(date.toString())) { streak++; date = date.minusDays(1) }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("widget_points", stats.optInt("total").toString())
                .putString("widget_streak", streak.toString())
                .putBoolean("widget_signed_in", !account.isNull("user"))
                .apply()
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, AuraWidgetProvider::class.java))
            ids.forEach { updateWidget(context, manager, it) }
        }

        private fun updateWidget(context: Context, manager: AppWidgetManager, id: Int) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val signedIn = prefs.getBoolean("widget_signed_in", false)
            val views = RemoteViews(context.packageName, R.layout.aura_widget).apply {
                setTextViewText(R.id.widgetPoints, if (signedIn) prefs.getString("widget_points", "0") else "∞")
                setTextViewText(R.id.widgetStreak, if (signedIn) "${prefs.getString("widget_streak", "0")} day streak" else "Sign in to sync your progress")
                setTextViewText(R.id.widgetHint, if (signedIn) "TAP TO OPEN AURA" else "OPEN AURA TO GET STARTED")
                val open = PendingIntent.getActivity(context, id, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                setOnClickPendingIntent(R.id.widgetRoot, open)
            }
            manager.updateAppWidget(id, views)
        }
    }
}
