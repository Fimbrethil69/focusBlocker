package com.focusblocker.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.focusblocker.core.BlockConfig
import com.focusblocker.core.BlockKind
import com.focusblocker.core.INSTAGRAM_PACKAGE
import com.focusblocker.data.ConfigRepository
import com.focusblocker.data.SocialApps
import com.focusblocker.data.StatsRepository
import com.focusblocker.ui.BlockActivity
import java.time.LocalDateTime

/**
 * Surveille l'application au premier plan :
 *  - réseaux sociaux pendant une plage bloquée → retour à l'accueil + écran de blocage ;
 *  - lecteur Shorts de YouTube (à toute heure) → retour arrière ;
 *  - si l'option est cochée, Instagram reste ouvert pendant le blocage tant qu'on est dans les messages.
 */
class BlockerAccessibilityService : AccessibilityService() {
    private lateinit var repository: ConfigRepository
    private lateinit var stats: StatsRepository
    private lateinit var diagnostics: InstagramDiagnostics
    private val handler = Handler(Looper.getMainLooper())
    private var lastActionAt = 0L
    private var lastShortsCheckAt = 0L
    private var lastInstagramCheckAt = 0L
    private var lastDiagnosticAt = 0L
    private var lastToastAt = 0L
    private var instagramRecheckPending = false

    // Revérifie l'écran Instagram un peu plus tard : au démarrage ou à l'ouverture d'une
    // notification, Instagram affiche brièvement d'autres écrans avant la conversation.
    private val instagramRecheck = Runnable {
        instagramRecheckPending = false
        val now = LocalDateTime.now()
        val config = repository.current(now)
        val root = rootInActiveWindow
        if (root?.packageName?.toString() == INSTAGRAM_PACKAGE &&
            instagramDirectOnly(config, now) &&
            SystemClock.elapsedRealtime() >= directGraceUntil &&
            !isInstagramDirectVisible()
        ) {
            block(INSTAGRAM_PACKAGE, SystemClock.elapsedRealtime())
        }
    }

    override fun onServiceConnected() {
        repository = ConfigRepository.get(this)
        stats = StatsRepository.get(this)
        diagnostics = InstagramDiagnostics.get(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || !::repository.isInitialized) return

        val nowMs = SystemClock.elapsedRealtime()
        // Laisse le temps à l'action précédente de s'appliquer, pour ne pas boucler.
        if (nowMs - lastActionAt < ACTION_COOLDOWN_MS) return

        if (pkg == INSTAGRAM_PACKAGE && diagnostics.enabled && nowMs - lastDiagnosticAt > DIAGNOSTIC_INTERVAL_MS) {
            lastDiagnosticAt = nowMs
            rootInActiveWindow?.takeIf { it.packageName?.toString() == INSTAGRAM_PACKAGE }?.let(diagnostics::capture)
        }

        val now = LocalDateTime.now()
        val config = repository.current(now)
        if (pkg == INSTAGRAM_PACKAGE && instagramDirectOnly(config, now)) {
            checkInstagramDirect(event, nowMs)
            return
        }
        if (pkg in config.blockedPackages && config.isBlockedAt(now)) {
            block(pkg, nowMs)
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
                stats.record(BlockKind.SHORTS)
                if (nowMs - lastToastAt > 5_000) {
                    lastToastAt = nowMs
                    Toast.makeText(this, "Shorts bloqués", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun block(pkg: String, nowMs: Long) {
        lastActionAt = nowMs
        performGlobalAction(GLOBAL_ACTION_HOME)
        startActivity(BlockActivity.intent(this, pkg))
        stats.record(BlockKind.SOCIAL)
    }

    private fun instagramDirectOnly(config: BlockConfig, now: LocalDateTime): Boolean =
        config.allowInstagramDirect && INSTAGRAM_PACKAGE in config.blockedPackages && config.isBlockedAt(now)

    private fun checkInstagramDirect(event: AccessibilityEvent, nowMs: Long) {
        if (nowMs < directGraceUntil) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            nowMs - lastInstagramCheckAt < INSTAGRAM_CHECK_INTERVAL_MS
        ) return
        lastInstagramCheckAt = nowMs
        if (isInstagramDirectVisible()) {
            handler.removeCallbacks(instagramRecheck)
            instagramRecheckPending = false
        } else if (!instagramRecheckPending) {
            instagramRecheckPending = true
            handler.postDelayed(instagramRecheck, INSTAGRAM_RECHECK_DELAY_MS)
        }
    }

    private fun isInstagramDirectVisible(): Boolean {
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() != INSTAGRAM_PACKAGE) return false
        val scan = InstagramScreenScan().also { it.visit(root, depth = 0) }
        if (scan.forbidden) return false
        return scan.messageComposer || INSTAGRAM_DIRECT_VIEW_IDS.any { id ->
            root.findAccessibilityNodeInfosByViewId("$INSTAGRAM_PACKAGE:id/$id")
                .any(AccessibilityNodeInfo::isVisibleToUser)
        }
    }

    /**
     * Parcourt l'écran Instagram :
     *  - une zone de saisie « Message… » visible signale une conversation, quels que soient
     *    les identifiants internes d'Instagram ;
     *  - le fil, les Stories et les Reels sont refusés même si une telle zone est visible
     *    (réponse à une Story, partage d'une publication).
     */
    private class InstagramScreenScan {
        var forbidden = false
        var messageComposer = false
        private var visited = 0

        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (forbidden || depth > 60 || ++visited > 1_500) return
            if (node.isVisibleToUser) {
                val id = node.viewIdResourceName?.removePrefix("$INSTAGRAM_PACKAGE:id/").orEmpty()
                if (INSTAGRAM_FORBIDDEN_ID_PREFIXES.any { id.startsWith(it) }) {
                    forbidden = true
                    return
                }
                // Selon les versions, le texte d'aide est exposé comme « hint » ou comme texte du champ vide.
                val hint = (node.hintText ?: node.text)?.toString().orEmpty()
                if (node.isEditable && hint.contains("message", ignoreCase = true)) {
                    messageComposer = true
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { visit(it, depth + 1) }
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

    override fun onDestroy() {
        handler.removeCallbacks(instagramRecheck)
        super.onDestroy()
    }

    companion object {
        private const val ACTION_COOLDOWN_MS = 1_200L
        private const val SHORTS_CHECK_INTERVAL_MS = 400L
        private const val INSTAGRAM_CHECK_INTERVAL_MS = 400L
        private const val INSTAGRAM_RECHECK_DELAY_MS = 1_500L
        private const val DIAGNOSTIC_INTERVAL_MS = 1_000L
        private const val DIRECT_GRACE_MS = 5_000L

        // Identifiants des vues de la liste des conversations et d'une conversation Instagram.
        // Instagram les renomme parfois : le diagnostic de l'application aide à les retrouver.
        private val INSTAGRAM_DIRECT_VIEW_IDS = listOf(
            "row_thread_composer_edittext",
            "row_thread_composer_container",
            "direct_thread_toolbar",
            "inbox_refreshable_thread_list_recyclerview",
            "direct_inbox_container",
        )

        // Début des identifiants du fil, des Stories et des Reels : jamais autorisés.
        private val INSTAGRAM_FORBIDDEN_ID_PREFIXES = listOf("row_feed_", "reel_viewer", "clips_viewer")

        /** Jusqu'à cet instant, Instagram n'est pas vérifié : le temps d'ouvrir les messages. */
        @Volatile
        private var directGraceUntil = 0L

        fun allowInstagramDirectLaunch() {
            directGraceUntil = SystemClock.elapsedRealtime() + DIRECT_GRACE_MS
        }

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
