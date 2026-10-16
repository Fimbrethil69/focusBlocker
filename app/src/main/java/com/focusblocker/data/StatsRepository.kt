package com.focusblocker.data

import android.content.Context
import com.focusblocker.core.BlockCount
import com.focusblocker.core.BlockKind
import com.focusblocker.core.BlockStats
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** Compteur des ouvertures bloquées, stocké jour par jour dans les SharedPreferences. */
class StatsRepository private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("focus_stats", Context.MODE_PRIVATE)

    @Volatile
    private var cached: BlockStats? = null

    fun load(): BlockStats = cached ?: decode(prefs.getString(KEY_DAYS, null)).also { cached = it }

    @Synchronized
    fun record(kind: BlockKind, date: LocalDate = LocalDate.now()) {
        val stats = load().record(date, kind)
        cached = stats
        prefs.edit().putString(KEY_DAYS, encode(stats)).apply()
    }

    companion object {
        private const val KEY_DAYS = "days"

        @Volatile
        private var instance: StatsRepository? = null

        fun get(context: Context): StatsRepository =
            instance ?: synchronized(this) { instance ?: StatsRepository(context).also { instance = it } }

        // Format : {"2026-10-16": [réseaux sociaux, Shorts], …}
        private fun encode(stats: BlockStats): String = JSONObject().apply {
            stats.days.forEach { (date, count) -> put(date.toString(), JSONArray().put(count.social).put(count.shorts)) }
        }.toString()

        private fun decode(json: String?): BlockStats {
            if (json == null) return BlockStats()
            return try {
                val o = JSONObject(json)
                BlockStats(o.keys().asSequence().associate { key ->
                    val a = o.getJSONArray(key)
                    LocalDate.parse(key) to BlockCount(a.getInt(0), a.getInt(1))
                })
            } catch (e: Exception) {
                BlockStats()
            }
        }
    }
}
