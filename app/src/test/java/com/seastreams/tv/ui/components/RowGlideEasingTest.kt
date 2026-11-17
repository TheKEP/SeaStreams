package com.seastreams.tv.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import com.seastreams.tv.core.datastore.CatalogRowScrollSpeed
import com.seastreams.tv.ui.screens.settings.scrollSpeedOptionLabel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RowGlideEasingTest {

    @Test
    fun `a fresh move eases in and out`() {
        assertSame(FastOutSlowInEasing, rowGlideEasing(interruptsGlide = false))
    }

    @Test
    fun `a move that interrupts a running glide keeps its momentum`() {
        assertSame(LinearOutSlowInEasing, rowGlideEasing(interruptsGlide = true))
        // Starts faster than a fresh glide, so retargeting never stalls the row.
        assertTrue(glideProgress(0.1f, rowGlideEasing(true)) > glideProgress(0.1f, rowGlideEasing(false)))
    }

    @Test
    fun `both glide curves start at zero and land exactly at the target`() {
        listOf(true, false).forEach { interrupts ->
            val easing = rowGlideEasing(interrupts)
            assertEquals(0f, glideProgress(0f, easing))
            assertEquals(1f, glideProgress(1f, easing))
            assertEquals(1f, glideProgress(1.5f, easing))
        }
    }

    @Test
    fun `progress at each speed reaches the end only after its scroll duration`() {
        CatalogRowScrollSpeed.entries.forEach { speed ->
            val duration = speed.scrollDurationMs
            assertTrue(glideProgress((duration / 2f) / duration) < 1f, "$speed half way")
            assertEquals(1f, glideProgress(duration.toFloat() / duration), "$speed at the end")
        }
    }

    @Test
    fun `the default speed is marked in the picker`() {
        assertEquals("Slow (default)", scrollSpeedOptionLabel(CatalogRowScrollSpeed.SLOW))
        assertEquals("Medium", scrollSpeedOptionLabel(CatalogRowScrollSpeed.MEDIUM))
        assertEquals("Fast", scrollSpeedOptionLabel(CatalogRowScrollSpeed.FAST))
    }
}
