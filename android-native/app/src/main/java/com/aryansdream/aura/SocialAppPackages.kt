package com.aryansdream.aura

/** Package names used only to filter optional social-app notification alerts. */
object SocialAppPackages {
    private val packages = setOf(
        "com.instagram.android", "com.zhiliaoapp.musically", "com.ss.android.ugc.trill",
        "com.facebook.katana", "com.facebook.orca", "com.facebook.lite",
        "com.snapchat.android", "com.twitter.android", "com.x.android",
        "com.threads.android", "com.reddit.frontpage", "com.pinterest"
    )

    fun contains(packageName: String?): Boolean = packageName != null && packageName in packages
}
