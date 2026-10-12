package com.focusblocker.data

data class SocialApp(val name: String, val packageName: String, val blockedByDefault: Boolean)

object SocialApps {
    const val YOUTUBE = "com.google.android.youtube"

    val all = listOf(
        SocialApp("Instagram", "com.instagram.android", true),
        SocialApp("TikTok", "com.zhiliaoapp.musically", true),
        SocialApp("TikTok (variante)", "com.ss.android.ugc.trill", true),
        SocialApp("Facebook", "com.facebook.katana", true),
        SocialApp("Facebook Lite", "com.facebook.lite", true),
        SocialApp("Snapchat", "com.snapchat.android", true),
        SocialApp("X (Twitter)", "com.twitter.android", true),
        SocialApp("Threads", "com.instagram.barcelona", true),
        SocialApp("Reddit", "com.reddit.frontpage", true),
        SocialApp("Pinterest", "com.pinterest", false),
        SocialApp("LinkedIn", "com.linkedin.android", false),
        SocialApp("BeReal", "com.bereal.ft", false),
        SocialApp("YouTube (application entière)", YOUTUBE, false),
    )

    val defaultBlocked: Set<String> = all.filter { it.blockedByDefault }.map { it.packageName }.toSet()

    fun nameOf(packageName: String): String = all.firstOrNull { it.packageName == packageName }?.name ?: packageName
}
