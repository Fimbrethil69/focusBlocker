package com.focusblocker.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** Plage horaire en minutes depuis minuit. Si [endMinute] < [startMinute], la plage passe minuit. */
data class TimeRange(val startMinute: Int, val endMinute: Int) {
    init {
        require(startMinute in 0 until MINUTES_PER_DAY) { "Début invalide : $startMinute" }
        require(endMinute in 0 until MINUTES_PER_DAY) { "Fin invalide : $endMinute" }
        require(startMinute != endMinute) { "Une plage ne peut pas être vide" }
    }

    val wrapsMidnight: Boolean get() = endMinute < startMinute

    override fun toString(): String = "${formatMinute(startMinute)} → ${formatMinute(endMinute)}"

    companion object {
        fun of(startHour: Int, startMin: Int, endHour: Int, endMin: Int) =
            TimeRange(startHour * 60 + startMin, endHour * 60 + endMin)
    }
}

data class BlockConfig(
    val ranges: List<TimeRange>,
    val days: Set<DayOfWeek>,
    val blockedPackages: Set<String>,
) {
    fun isBlockedAt(time: LocalDateTime): Boolean {
        val minute = time.hour * 60 + time.minute
        val today = time.dayOfWeek
        val yesterday = today.minus(1)
        return ranges.any { r ->
            if (!r.wrapsMidnight) {
                today in days && minute >= r.startMinute && minute < r.endMinute
            } else {
                // Une plage qui passe minuit appartient au jour où elle commence.
                (today in days && minute >= r.startMinute) || (yesterday in days && minute < r.endMinute)
            }
        }
    }

    /** Premier instant (à la minute) où l'état bloqué/libre change après [time], ou null si jamais. */
    fun nextChange(time: LocalDateTime): LocalDateTime? {
        val start = time.truncatedTo(ChronoUnit.MINUTES)
        val current = isBlockedAt(start)
        var t = start.plusMinutes(1)
        val limit = start.plusDays(8)
        while (t.isBefore(limit)) {
            if (isBlockedAt(t) != current) return t
            t = t.plusMinutes(1)
        }
        return null
    }

    /**
     * Compare [newConfig] à cette configuration.
     * Plus souple dès qu'une application ou une minute de la semaine n'est plus bloquée,
     * même si d'autres réglages deviennent plus stricts en même temps.
     */
    fun changeTo(newConfig: BlockConfig): ConfigChange {
        val appRemoved = !newConfig.blockedPackages.containsAll(blockedPackages)
        val appAdded = !blockedPackages.containsAll(newConfig.blockedPackages)
        var lessTime = false
        var moreTime = false
        var t = REFERENCE_MONDAY
        repeat(7 * MINUTES_PER_DAY) {
            val before = isBlockedAt(t)
            val after = newConfig.isBlockedAt(t)
            if (before && !after) lessTime = true
            if (!before && after) moreTime = true
            t = t.plusMinutes(1)
        }
        return when {
            appRemoved || lessTime -> ConfigChange.LOOSER
            appAdded || moreTime -> ConfigChange.STRICTER
            else -> ConfigChange.NONE
        }
    }

    companion object {
        val DEFAULT_RANGES = listOf(TimeRange.of(9, 0, 12, 0), TimeRange.of(14, 0, 20, 0))
        val ALL_DAYS: Set<DayOfWeek> = DayOfWeek.entries.toSet()

        // Un lundi quelconque : sert à parcourir les minutes d'une semaine type.
        private val REFERENCE_MONDAY = LocalDateTime.of(2024, 1, 1, 0, 0)
    }
}

enum class ConfigChange { NONE, STRICTER, LOOSER }

/**
 * Configuration en vigueur + éventuelle configuration en attente.
 *
 * Règles anti-contournement :
 *  - on ne peut modifier la configuration que pendant une période libre ;
 *  - une modification ne prend effet que le lendemain (sinon on pourrait, juste avant
 *    une plage bloquée, supprimer cette plage).
 */
data class ConfigState(
    val active: BlockConfig,
    val pending: BlockConfig? = null,
    val pendingFrom: LocalDate? = null,
    /** Date du dernier assouplissement entré en vigueur, ou null s'il n'y en a jamais eu. */
    val loosenedOn: LocalDate? = null,
    /** Date du dernier renforcement entré en vigueur depuis le dernier assouplissement. */
    val tightenedOn: LocalDate? = null,
) {
    /** Applique la configuration en attente si sa date d'effet est atteinte. */
    fun resolved(today: LocalDate): ConfigState =
        if (pending != null && pendingFrom != null && !today.isBefore(pendingFrom)) {
            when (active.changeTo(pending)) {
                ConfigChange.LOOSER -> ConfigState(pending, loosenedOn = pendingFrom)
                ConfigChange.STRICTER -> ConfigState(pending, loosenedOn = loosenedOn, tightenedOn = pendingFrom)
                ConfigChange.NONE -> ConfigState(pending, loosenedOn = loosenedOn, tightenedOn = tightenedOn)
            }
        } else {
            this
        }

    val pendingChange: ConfigChange
        get() = pending?.let { active.changeTo(it) } ?: ConfigChange.NONE

    /** Jours sans assouplissement des paramètres, comptés depuis [fallback] s'il n'y en a jamais eu. */
    fun daysWithoutLoosening(today: LocalDate, fallback: LocalDate): Long {
        val since = resolved(today).loosenedOn ?: fallback
        return ChronoUnit.DAYS.between(since, today).coerceAtLeast(0)
    }

    fun isBlockedAt(time: LocalDateTime): Boolean = resolved(time.toLocalDate()).active.isBlockedAt(time)

    fun canEdit(time: LocalDateTime): Boolean = !isBlockedAt(time)

    fun withEdit(newConfig: BlockConfig, time: LocalDateTime): ConfigState {
        check(canEdit(time)) { "Modification interdite pendant une plage de blocage" }
        return resolved(time.toLocalDate()).copy(
            pending = newConfig,
            pendingFrom = time.toLocalDate().plusDays(1),
        )
    }

    fun withoutPending(time: LocalDateTime): ConfigState {
        check(canEdit(time)) { "Modification interdite pendant une plage de blocage" }
        return resolved(time.toLocalDate()).copy(pending = null, pendingFrom = null)
    }
}

const val MINUTES_PER_DAY = 24 * 60

fun formatMinute(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)
