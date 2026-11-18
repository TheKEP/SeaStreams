package com.seastreams.tv.ui.screens.settings

import com.seastreams.tv.core.datastore.BackgroundSwatches
import com.seastreams.tv.ui.theme.EdgeKeyResult
import com.seastreams.tv.ui.theme.edgeKeyResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BackgroundSwatchStripTest {

    private val last = BackgroundSwatches.ALL.lastIndex
    private val espresso = 0xFF472A11.toInt()

    @Test
    fun `right on the last swatch wraps to black`() {
        assertEquals(espresso, BackgroundSwatches.ALL[last])
        val target = swatchWrapTarget(last, last, towardsEnd = true)
        assertEquals(0, target)
        assertEquals(BackgroundSwatches.DEFAULT, BackgroundSwatches.ALL[requireNotNull(target)])
    }

    @Test
    fun `left on black wraps to the last swatch`() {
        val target = swatchWrapTarget(0, last, towardsEnd = false)
        assertEquals(last, target)
        assertEquals(espresso, BackgroundSwatches.ALL[requireNotNull(target)])
    }

    @Test
    fun `inner swatches and the inward key at each edge move focus normally`() {
        (1 until last).forEach { index ->
            assertNull(swatchWrapTarget(index, last, towardsEnd = true), "right on $index")
            assertNull(swatchWrapTarget(index, last, towardsEnd = false), "left on $index")
        }
        assertNull(swatchWrapTarget(0, last, towardsEnd = true))
        assertNull(swatchWrapTarget(last, last, towardsEnd = false))
    }

    @Test
    fun `a single swatch wraps onto itself both ways`() {
        assertEquals(0, swatchWrapTarget(0, 0, towardsEnd = true))
        assertEquals(0, swatchWrapTarget(0, 0, towardsEnd = false))
    }

    @Test
    fun `a fresh press at either edge wraps`() {
        listOf(last to true, 0 to false).forEach { (edge, towardsEnd) ->
            assertEquals(EdgeKeyResult.WRAP, edgeAction(edge, towardsEnd, repeatCount = 0), "edge $edge")
        }
    }

    @Test
    fun `a held key stops on either edge swatch without wrapping`() {
        listOf(last to true, 0 to false).forEach { (edge, towardsEnd) ->
            listOf(1, 2, 10).forEach { repeats ->
                assertEquals(EdgeKeyResult.HOLD, edgeAction(edge, towardsEnd, repeats), "edge $edge repeat $repeats")
            }
        }
    }

    @Test
    fun `holding then releasing and pressing again wraps`() {
        assertEquals(EdgeKeyResult.HOLD, edgeAction(last, towardsEnd = true, repeatCount = 4))
        assertEquals(EdgeKeyResult.WRAP, edgeAction(last, towardsEnd = true, repeatCount = 0))
    }

    /** What the strip does with a key-down on swatch [index]: edge handling only where a wrap target exists. */
    private fun edgeAction(index: Int, towardsEnd: Boolean, repeatCount: Int): EdgeKeyResult = edgeKeyResult(
        matchesKey = swatchWrapTarget(index, last, towardsEnd) != null,
        isKeyDown = true,
        repeatCount = repeatCount,
    )
}
