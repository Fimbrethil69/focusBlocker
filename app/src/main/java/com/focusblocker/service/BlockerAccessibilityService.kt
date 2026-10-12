package com.focusblocker.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.focusblocker.data.ConfigRepository
import com.focusblocker.data.SocialApps
import com.focusblocker.ui.BlockActivity
import java.time.LocalDateTime

/**
 * Surveille l'application au premier plan :
 *  - réseaux sociaux pendant une plage bloquée → retour à l'accueil + écran de blocage ;
 *  - lecteur Shorts de YouTube (à toute heure) → retour arrière.
 */
class BlockerAccessibilityService : AccessibilityService() {
    private lateinit var repository: ConfigRepository
    private var lastActionAt = 0L
    private var lastShortsCheckAt = 0L
    private var lastToastAt = 0L

    override fun onServiceConnected() {
        repository = ConfigRepository.get(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || !::repository.isInitialized) return

        val nowMs = SystemClock.elapsedRealtime()
        // Laisse le temps à l'action précédente de s'appliquer, pour ne pas boucler.
        if (nowMs - lastActionAt < ACTION_COOLDOWN_MS) return

        val now = LocalDateTime.now()
        val config = repository.current(now)
        if (pkg in config.blockedPackages && config.isBlockedAt(now)) {
            lastActionAt = nowMs
            performGlobalAction(GLOBAL_ACTION_HOME)
            startActivity(BlockActivity.intent(this, pkg))
            return
        }

        if (pkg == SocialApps.YOUTUBE) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
                nowMs - lastShortsCheckAt < SHORTS_CHECK_INTERVAL_MS
            ) return
            lastShortsCheckAt = nowMs
            if (isShortsVisible()) {
                lastActionAt = nowMs
                performGlobalAction(GLOBAL_ACTION_BACK)
                if (nowMs - lastToastAt > 5_000) {
                    lastToastAt = nowMs
                    Toast.makeText(this, "Shorts bloqués", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun isShortsVisible(): Boolean {
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() != SocialApps.YOUTUBE) return false
        return SHORTS_VIEW_IDS.any { id ->
            root.findAccessibilityNodeInfosByViewId("${SocialApps.YOUTUBE}:id/$id")
                .any(AccessibilityNodeInfo::isVisibleToUser)
        }
    }

    override fun onInterrupt() = Unit

    companion object {
        private const val ACTION_COOLDOWN_MS = 1_200L
        private const val SHORTS_CHECK_INTERVAL_MS = 400L

        // Identifiants des vues du lecteur Shorts dans l'application YouTube.
        // À compléter si une mise à jour de YouTube les renomme.
        private val SHORTS_VIEW_IDS = listOf(
            "reel_recycler",
            "reel_player_page_container",
            "reel_watch_player",
            "reel_watch_fragment_root",
        )

        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, BlockerAccessibilityService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
        }
    }
}
