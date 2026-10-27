package com.aryansdream.aura

/** Identifies the social packages AURA can cover during an active focus session. */
object FocusAppPolicy {
    fun isDistracting(packageName: String?): Boolean = SocialAppPackages.contains(packageName)
}
