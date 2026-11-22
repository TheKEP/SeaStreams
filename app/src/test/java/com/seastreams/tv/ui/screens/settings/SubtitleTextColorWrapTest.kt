package com.seastreams.tv.ui.screens.settings

import com.seastreams.tv.ui.theme.EdgeKeyResult
import com.seastreams.tv.ui.theme.edgeKeyResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The Subtitle styling Text color swatches wrap both ways under the §1 edge-stop-then-wrap rule. */
class SubtitleTextColorWrapTest {

    private val last = SUBTITLE_COLORS.lastIndex

    @Test
    fun `the swatches run White to Light grey`() {
        assertEquals(listOf("White", "Yellow", "Cyan", "Green", "Pink", "Light grey"), SUBTITLE_COLORS.map { it.first })
    }

    @Test
    fun `fresh Left on White wraps to Light grey and fresh Right on Light grey wraps to White`() {
        assertEquals("Light grey", SUBTITLE_COLORS[requireNotNull(swatchWrapTarget(0, last, towardsEnd = false))].first)
        assertEquals("White", SUBTITLE_COLORS[requireNotNull(swatchWrapTarget(last, last, towardsEnd = true))].first)
        assertEquals(EdgeKeyResult.WRAP, edgeKeyResult(matchesKey = true, isKeyDown = true, repeatCount = 0))
    }

    @Test
    fun `held Left or Right stops on the edge swatch`() {
        listOf(1, 3, 10).forEach { repeats ->
            assertEquals(EdgeKeyResult.HOLD, edgeKeyResult(matchesKey = true, isKeyDown = true, repeatCount = repeats), "repeat $repeats")
        }
    }

    @Test
    fun `middle swatches and inward keys at the edges move focus normally`() {
        (1 until last).forEach { index ->
            assertNull(swatchWrapTarget(index, last, towardsEnd = true), "right on $index")
            assertNull(swatchWrapTarget(index, last, towardsEnd = false), "left on $index")
        }
        assertNull(swatchWrapTarget(0, last, towardsEnd = true))
        assertNull(swatchWrapTarget(last, last, towardsEnd = false))
    }
}
