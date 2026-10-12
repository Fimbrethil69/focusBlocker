package com.focusblocker.data

import android.content.Context
import android.content.SharedPreferences
import com.focusblocker.core.BlockConfig
import com.focusblocker.core.ConfigState
import com.focusblocker.core.TimeRange
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Stocke la configuration dans les SharedPreferences.
 * Garde une copie décodée en mémoire : le service d'accessibilité la lit à chaque événement.
 */
class ConfigRepository private constructor(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("focus_blocker", Context.MODE_PRIVATE)

    @Volatile
    private var cached: ConfigState? = null

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> cached = null }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun load(): ConfigState {
        val state = cached ?: decode(prefs.getString(KEY_STATE, null)).also { cached = it }
        // Fait passer la configuration en attente en vigueur dès que sa date est atteinte.
        val resolved = state.resolved(LocalDate.now())
        if (resolved != state) save(resolved)
        return resolved
    }

    fun current(now: LocalDateTime = LocalDateTime.now()): BlockConfig =
        load().resolved(now.toLocalDate()).active

    fun save(state: ConfigState) {
        cached = state
        prefs.edit().putString(KEY_STATE, encode(state)).apply()
    }

    companion object {
        private const val KEY_STATE = "state"

        @Volatile
        private var instance: ConfigRepository? = null

        fun get(context: Context): ConfigRepository =
            instance ?: synchronized(this) { instance ?: ConfigRepository(context).also { instance = it } }

        val DEFAULT = ConfigState(
            BlockConfig(BlockConfig.DEFAULT_RANGES, BlockConfig.ALL_DAYS, SocialApps.defaultBlocked),
        )

        private fun encode(state: ConfigState): String = JSONObject().apply {
            put("active", encodeConfig(state.active))
            state.pending?.let { put("pending", encodeConfig(it)) }
            state.pendingFrom?.let { put("pendingFrom", it.toString()) }
        }.toString()

        private fun encodeConfig(config: BlockConfig) = JSONObject().apply {
            put("ranges", JSONArray().apply {
                config.ranges.forEach { put(JSONArray().put(it.startMinute).put(it.endMinute)) }
            })
            put("days", JSONArray().apply { config.days.forEach { put(it.value) } })
            put("packages", JSONArray().apply { config.blockedPackages.forEach { put(it) } })
        }

        private fun decode(json: String?): ConfigState {
            if (json == null) return DEFAULT
            return try {
                val o = JSONObject(json)
                ConfigState(
                    active = decodeConfig(o.getJSONObject("active")),
                    pending = o.optJSONObject("pending")?.let(::decodeConfig),
                    pendingFrom = if (o.has("pendingFrom")) LocalDate.parse(o.getString("pendingFrom")) else null,
                )
            } catch (e: Exception) {
                DEFAULT
            }
        }

        private fun decodeConfig(o: JSONObject): BlockConfig {
            val ranges = o.getJSONArray("ranges").let { a ->
                (0 until a.length()).map { i -> a.getJSONArray(i).let { TimeRange(it.getInt(0), it.getInt(1)) } }
            }
            val days = o.getJSONArray("days").let { a -> (0 until a.length()).map { DayOfWeek.of(a.getInt(it)) }.toSet() }
            val packages = o.getJSONArray("packages").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
            return BlockConfig(ranges, days, packages)
        }
    }
}
