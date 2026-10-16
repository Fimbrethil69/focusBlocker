package com.focusblocker.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

enum class BlockKind { SOCIAL, SHORTS }

data class BlockCount(val social: Int = 0, val shorts: Int = 0) {
    val total: Int get() = social + shorts

    operator fun plus(other: BlockCount) = BlockCount(social + other.social, shorts + other.shorts)

    fun incremented(kind: BlockKind) = when (kind) {
        BlockKind.SOCIAL -> copy(social = social + 1)
        BlockKind.SHORTS -> copy(shorts = shorts + 1)
    }
}

/** Nombre d'ouvertures bloquées, jour par jour. */
data class BlockStats(val days: Map<LocalDate, BlockCount> = emptyMap()) {
    fun record(date: LocalDate, kind: BlockKind): BlockStats =
        BlockStats(days + (date to day(date).incremented(kind)))

    fun day(date: LocalDate): BlockCount = days[date] ?: BlockCount()

    /** Du lundi de la semaine de [date] jusqu'à [date]. */
    fun week(date: LocalDate): BlockCount =
        between(date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), date)

    /** Du 1er du mois de [date] jusqu'à [date]. */
    fun month(date: LocalDate): BlockCount = between(date.withDayOfMonth(1), date)

    fun total(): BlockCount = days.values.fold(BlockCount(), BlockCount::plus)

    /** Total du [from] au [to] inclus. */
    fun between(from: LocalDate, to: LocalDate): BlockCount =
        days.filterKeys { !it.isBefore(from) && !it.isAfter(to) }.values.fold(BlockCount(), BlockCount::plus)
}
