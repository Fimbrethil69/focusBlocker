package com.focusblocker.service

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import com.focusblocker.core.INSTAGRAM_PACKAGE
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Relève les identifiants des vues des derniers écrans Instagram affichés, pour savoir
 * lesquels désignent l'écran des messages (ils changent d'une version d'Instagram à l'autre).
 * Désactivé par défaut ; rien ne quitte le téléphone, l'utilisateur copie le relevé lui-même.
 */
class InstagramDiagnostics private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("focus_diagnostics", Context.MODE_PRIVATE)
    private var lastIds: Set<String> = emptySet()

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    val report: String get() = prefs.getString(KEY_REPORT, "").orEmpty()

    fun clear() {
        lastIds = emptySet()
        prefs.edit().remove(KEY_REPORT).apply()
    }

    fun capture(root: AccessibilityNodeInfo) {
        val ids = sortedSetOf<String>()
        collect(root, ids, depth = 0)
        if (ids.isEmpty() || ids == lastIds) return
        lastIds = ids
        val time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        val screens = report.split(SEPARATOR).filter { it.isNotBlank() }.takeLast(MAX_SCREENS - 1)
        prefs.edit().putString(KEY_REPORT, (screens + "$time : ${ids.joinToString(", ")}").joinToString(SEPARATOR)).apply()
    }

    private fun collect(node: AccessibilityNodeInfo, ids: MutableSet<String>, depth: Int) {
        if (depth > 60 || ids.size > 400) return
        node.viewIdResourceName?.let { if (node.isVisibleToUser) ids += it.removePrefix("$INSTAGRAM_PACKAGE:id/") }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collect(it, ids, depth + 1) }
        }
    }

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_REPORT = "report"
        private const val SEPARATOR = "\n\n"
        private const val MAX_SCREENS = 12

        @Volatile
        private var instance: InstagramDiagnostics? = null

        fun get(context: Context): InstagramDiagnostics =
            instance ?: synchronized(this) { instance ?: InstagramDiagnostics(context).also { instance = it } }
    }
}
