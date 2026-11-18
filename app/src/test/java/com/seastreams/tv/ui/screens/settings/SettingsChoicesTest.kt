package com.seastreams.tv.ui.screens.settings

import com.seastreams.tv.core.datastore.BackgroundSwatches
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class SettingsChoicesTest {

    @Test
    fun `auto-play on - holding Play shows the stream results`() {
        assertEquals("Press and hold Play to view stream results", autoPlayTopStreamDescription(enabled = true))
    }

    @Test
    fun `auto-play off - pressing Play shows the stream results`() {
        assertEquals("Press Play to view stream results", autoPlayTopStreamDescription(enabled = false))
    }

    @Test
    fun `the ochre swatch has its own name`() {
        assertEquals("Ochre", backgroundName(0xFF916405.toInt()))
    }

    @Test
    fun `every swatch has a distinct name and none shows a hex code`() {
        val names = BackgroundSwatches.ALL.map(::backgroundName)
        assertEquals(names.size, names.toSet().size, "$names")
        assertFalse(names.any { it.contains('#') })
    }

    @Test
    fun `an unknown colour reads as black`() {
        assertEquals("Black", backgroundName(0xFF123456.toInt()))
    }
}
