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
    fun `the new swatches have their own names`() {
        assertEquals("Muted purple", backgroundName(0xFF826098.toInt()))
        assertEquals("Midnight slate", backgroundName(0xFF0E1621.toInt()))
        assertEquals("Steel blue", backgroundName(0xFF17212B.toInt()))
    }

    @Test
    fun `the retired deep navy and espresso swatches read as black`() {
        assertEquals("Black", backgroundName(0xFF090B34.toInt()))
        assertEquals("Black", backgroundName(0xFF472A11.toInt()))
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
