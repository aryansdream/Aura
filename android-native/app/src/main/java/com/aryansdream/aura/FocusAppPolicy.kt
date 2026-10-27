package com.aryansdream.aura

import android.content.Context

/** Identifies the social packages AURA can cover during an active focus session. */
object FocusAppPolicy {
    fun blockedPackages(context: Context): Set<String> = context.getSharedPreferences("aura_native", Context.MODE_PRIVATE)
        .getStringSet("focus_blocklist", null)?.toSet() ?: SocialAppPackages.defaultBlockedPackages

    fun isDistracting(context: Context, packageName: String?): Boolean = packageName != null && packageName in blockedPackages(context)
}
