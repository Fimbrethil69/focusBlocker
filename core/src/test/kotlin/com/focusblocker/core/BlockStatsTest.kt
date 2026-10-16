package com.focusblocker.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class BlockStatsTest {
    private fun d(month: Int, day: Int) = LocalDate.of(2026, month, day)

    @Test
    fun countsByPeriod() {
        val stats = BlockStats()
            .record(d(9, 30), BlockKind.SOCIAL) // mois précédent
            .record(d(10, 11), BlockKind.SOCIAL) // dimanche : semaine précédente
            .record(d(10, 12), BlockKind.SHORTS) // lundi
            .record(d(10, 14), BlockKind.SOCIAL)
            .record(d(10, 14), BlockKind.SOCIAL)
            .record(d(10, 14), BlockKind.SHORTS)

        assertEquals(BlockCount(social = 2, shorts = 1), stats.day(d(10, 14)))
        assertEquals(BlockCount(), stats.day(d(10, 13)))
        assertEquals(BlockCount(social = 2, shorts = 2), stats.week(d(10, 14)))
        assertEquals(BlockCount(social = 3, shorts = 2), stats.month(d(10, 14)))
        assertEquals(BlockCount(social = 4, shorts = 2), stats.total())
        assertEquals(6, stats.total().total)
    }
}
