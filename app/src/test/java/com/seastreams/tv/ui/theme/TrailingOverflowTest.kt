package com.seastreams.tv.ui.theme

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TrailingOverflowTest {

    @Test
    fun `card fully inside the gutter needs no scroll`() {
        assertEquals(0, trailingOverflowPx(itemOffset = 1000, itemSize = 480, viewportEndOffset = 1848, afterContentPaddingPx = 72))
    }

    @Test
    fun `card ending exactly at the gutter needs no scroll`() {
        assertEquals(0, trailingOverflowPx(itemOffset = 1296, itemSize = 480, viewportEndOffset = 1848, afterContentPaddingPx = 72))
    }

    @Test
    fun `expanding View More past the right edge scrolls by the overflow plus gutter`() {
        // Settled at max scroll as a 180px poster, then widened to a 480px backdrop.
        assertEquals(300, trailingOverflowPx(itemOffset = 1596, itemSize = 480, viewportEndOffset = 1848, afterContentPaddingPx = 72))
    }
}
