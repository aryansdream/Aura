package com.aryansdream.aura

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.os.Handler
import android.os.Looper
import android.content.IntentFilter
import android.view.ViewGroup

class FocusBlockAccessibilityService : AccessibilityService() {
    private var blocker: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastForegroundPackage: String? = null
    private val delayedHide = Runnable { hideBlocker() }
    private val focusChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { inspectCurrentApp() }
    }
    private val checkActive = object : Runnable {
        override fun run() {
            val prefs = getSharedPreferences("aura_native", MODE_PRIVATE)
            if (!prefs.getBoolean("focus_running", false) || !prefs.getBoolean("block_social_apps", false)) hideBlocker()
            if (blocker != null) handler.postDelayed(this, 500)
        }
    }

    override fun onServiceConnected() {
        LanFocusSync.start(this)
        val filter = IntentFilter(FocusService.ACTION_FOCUS_STATE_CHANGED)
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(focusChangedReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(focusChangedReceiver, filter)
        serviceInfo = AccessibilityServiceInfo().apply {
            // TYPE_WINDOWS_CHANGED fires when our own overlay is added/removed. Treating that
            // event as a foreground-app change made the blocker repeatedly hide and reappear.
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 80
            flags = 0
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val prefs = getSharedPreferences("aura_native", MODE_PRIVATE)
        val active = prefs.getBoolean("focus_running", false) && prefs.getBoolean("block_social_apps", false)
        if (!active) { hideBlocker(); return }
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val foregroundPackage = event.packageName?.toString()?.takeIf { it.isNotBlank() } ?: return
        // System UI windows can be reported between the launcher and the app being opened.
        // Keep the current decision through those short transitions to avoid a visible flash.
        if (foregroundPackage in setOf("com.android.systemui", "com.vivo.systemuiplugin")) return
        // Adding the accessibility overlay generates AURA's own window event. Ignore it so
        // the overlay stays mounted until a real app switch occurs.
        if (foregroundPackage == packageName && blocker != null) return
        if (foregroundPackage == lastForegroundPackage) return
        lastForegroundPackage = foregroundPackage
        if (FocusAppPolicy.isDistracting(this, foregroundPackage)) {
            handler.removeCallbacks(delayedHide)
            showBlocker()
        } else if (blocker != null) {
            // App launches often emit a brief launcher/transition window before the final
            // activity. Delay removal so opening a blocked app doesn't reveal its content.
            handler.removeCallbacks(delayedHide)
            handler.postDelayed(delayedHide, if (foregroundPackage == "com.android.launcher3") 0 else 350)
        }
    }

    override fun onInterrupt() = hideBlocker()

    private fun inspectCurrentApp() {
        val prefs = getSharedPreferences("aura_native", MODE_PRIVATE)
        if (!prefs.getBoolean("focus_running", false) || !prefs.getBoolean("block_social_apps", false)) {
            hideBlocker(); return
        }
        val current = rootInActiveWindow?.packageName?.toString()
        if (FocusAppPolicy.isDistracting(this, current)) showBlocker() else if (blocker != null) hideBlocker()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(focusChangedReceiver) }
        hideBlocker()
        super.onDestroy()
    }

    private fun showBlocker() {
        if (blocker != null) return
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(32), dp(28), dp(32))
            setBackgroundColor(Color.rgb(12, 13, 18))
            isClickable = true
            isFocusable = true
        }
        root.addView(TextView(this).apply {
            text = "∞"
            textSize = 74f
            setTextColor(Color.rgb(181, 169, 255))
            gravity = Gravity.CENTER
        }, match())
        root.addView(TextView(this).apply {
            text = "Social app paused"
            textSize = 25f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }, match())
        root.addView(TextView(this).apply {
            text = "Your Focus session is still running. Close this app to return to your study session."
            textSize = 15f
            setTextColor(Color.rgb(177, 180, 193))
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(28))
        }, match())
        root.addView(Button(this).apply {
            text = "End focus session"
            setOnClickListener {
                startService(Intent(this@FocusBlockAccessibilityService, FocusService::class.java).setAction(FocusService.ACTION_STOP))
                hideBlocker()
                startActivity(Intent(this@FocusBlockAccessibilityService, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        runCatching {
            getSystemService(WindowManager::class.java).addView(root, params)
            blocker = root
            handler.post(checkActive)
        }
    }

    private fun hideBlocker() {
        handler.removeCallbacks(delayedHide)
        handler.removeCallbacks(checkActive)
        blocker?.let { view -> runCatching { getSystemService(WindowManager::class.java).removeView(view) } }
        blocker = null
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun match() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    companion object {
        fun isEnabled(context: android.content.Context): Boolean {
            val component = ComponentName(context, FocusBlockAccessibilityService::class.java)
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return enabled.split(':').mapNotNull(ComponentName::unflattenFromString).any { it == component }
        }
    }
}
