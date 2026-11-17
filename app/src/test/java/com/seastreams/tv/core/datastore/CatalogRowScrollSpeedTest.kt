package com.seastreams.tv.core.datastore

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource

class CatalogRowScrollSpeedTest {

    @ParameterizedTest
    @CsvSource(
        "SLOW, Slow, 480, 450",
        "MEDIUM, Medium, 280, 260",
        "FAST, Fast, 130, 120",
    )
    fun `each speed maps to its label and durations`(speed: CatalogRowScrollSpeed, label: String, scrollMs: Int, expandMs: Int) {
        assertEquals(label, speed.label)
        assertEquals(scrollMs, speed.scrollDurationMs)
        assertEquals(expandMs, speed.expandDurationMs)
    }

    @Test
    fun `slow is the default and speeds are listed slowest first`() {
        assertEquals(CatalogRowScrollSpeed.SLOW, CatalogRowScrollSpeed.DEFAULT)
        assertEquals(CatalogRowScrollSpeed.DEFAULT, AppearanceSettings().catalogRowScrollSpeed)
        val scrolls = CatalogRowScrollSpeed.entries.map { it.scrollDurationMs }
        assertEquals(scrolls.sortedDescending(), scrolls)
    }

    @ParameterizedTest
    @EnumSource(CatalogRowScrollSpeed::class)
    fun `a card finishes widening before its row's glide settles`(speed: CatalogRowScrollSpeed) {
        assertTrue(speed.expandDurationMs in 1..speed.scrollDurationMs)
    }
}
