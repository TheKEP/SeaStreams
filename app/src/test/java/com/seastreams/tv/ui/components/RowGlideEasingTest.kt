package com.seastreams.tv.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
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
    fun `rows always use the slow glide timing`() {
        assertEquals(480, CATALOG_ROW_GLIDE_MS)
        assertEquals(450, CARD_EXPAND_MS)
    }

    @Test
    fun `progress reaches the end only after the glide duration`() {
        val duration = CATALOG_ROW_GLIDE_MS.toFloat()
        assertTrue(glideProgress((duration / 2f) / duration) < 1f)
        assertEquals(1f, glideProgress(duration / duration))
    }

    @Test
    fun `a card finishes widening before its row's glide settles`() {
        assertTrue(CARD_EXPAND_MS in 1..CATALOG_ROW_GLIDE_MS)
    }
}
