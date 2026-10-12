package com.focusblocker.core

import java.time.DayOfWeek
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BlockConfigTest {
    private val config = BlockConfig(BlockConfig.DEFAULT_RANGES, BlockConfig.ALL_DAYS, setOf("com.instagram.android"))

    // Lundi 12 octobre 2026
    private fun at(day: Int, h: Int, m: Int) = LocalDateTime.of(2026, 10, day, h, m)

    @Test
    fun defaultSchedule() {
        assertFalse(config.isBlockedAt(at(12, 8, 59)))
        assertTrue(config.isBlockedAt(at(12, 9, 0)))
        assertTrue(config.isBlockedAt(at(12, 11, 59)))
        assertFalse(config.isBlockedAt(at(12, 12, 0)))
        assertFalse(config.isBlockedAt(at(12, 13, 59)))
        assertTrue(config.isBlockedAt(at(12, 14, 0)))
        assertTrue(config.isBlockedAt(at(12, 19, 59)))
        assertFalse(config.isBlockedAt(at(12, 20, 0)))
    }

    @Test
    fun daysAreRespected() {
        val weekdays = config.copy(days = BlockConfig.ALL_DAYS - DayOfWeek.SATURDAY - DayOfWeek.SUNDAY)
        assertTrue(weekdays.isBlockedAt(at(16, 10, 0))) // vendredi
        assertFalse(weekdays.isBlockedAt(at(17, 10, 0))) // samedi
    }

    @Test
    fun rangeAcrossMidnight() {
        val night = config.copy(ranges = listOf(TimeRange.of(22, 0, 6, 0)), days = setOf(DayOfWeek.MONDAY))
        assertTrue(night.isBlockedAt(at(12, 23, 0))) // lundi soir
        assertTrue(night.isBlockedAt(at(13, 5, 59))) // mardi matin, suite du lundi
        assertFalse(night.isBlockedAt(at(13, 6, 0)))
        assertFalse(night.isBlockedAt(at(13, 23, 0))) // mardi soir non coché
    }

    @Test
    fun nextChange() {
        assertEquals(at(12, 12, 0), config.nextChange(at(12, 10, 30)))
        assertEquals(at(12, 14, 0), config.nextChange(at(12, 12, 15)))
        assertEquals(at(13, 9, 0), config.nextChange(at(12, 21, 0)))
        assertNull(config.copy(ranges = emptyList()).nextChange(at(12, 10, 0)))
    }

    @Test
    fun editIsRefusedDuringBlockedPeriod() {
        val state = ConfigState(config)
        assertFalse(state.canEdit(at(12, 10, 0)))
        assertFailsWith<IllegalStateException> { state.withEdit(config.copy(ranges = emptyList()), at(12, 10, 0)) }
        assertTrue(state.canEdit(at(12, 12, 30)))
    }

    @Test
    fun editTakesEffectTomorrow() {
        val loosened = config.copy(ranges = emptyList())
        val state = ConfigState(config).withEdit(loosened, at(12, 13, 59))

        // Le jour même, l'ancienne configuration reste en vigueur.
        assertTrue(state.isBlockedAt(at(12, 14, 0)))
        // Le lendemain, la nouvelle s'applique.
        assertFalse(state.isBlockedAt(at(13, 10, 0)))
        assertEquals(ConfigState(loosened), state.resolved(at(13, 0, 0).toLocalDate()))
    }

    @Test
    fun cancellingPendingKeepsActive() {
        val state = ConfigState(config).withEdit(config.copy(ranges = emptyList()), at(12, 12, 30))
        assertEquals(ConfigState(config), state.withoutPending(at(12, 12, 45)))
    }

    @Test
    fun invalidRange() {
        assertFailsWith<IllegalArgumentException> { TimeRange(600, 600) }
        assertFailsWith<IllegalArgumentException> { TimeRange(-1, 600) }
    }
}
