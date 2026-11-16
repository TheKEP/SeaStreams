package com.seastreams.tv.ui.theme

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EdgeKeyActionTest {

    @Test
    fun `fresh press at the edge wraps`() {
        assertEquals(EdgeKeyAction.WRAP, edgeKeyAction(repeatCount = 0))
    }

    @Test
    fun `held key stops at the edge instead of wrapping`() {
        listOf(1, 2, 30, Int.MAX_VALUE).forEach { repeatCount ->
            assertEquals(EdgeKeyAction.HOLD, edgeKeyAction(repeatCount), "repeatCount=$repeatCount")
        }
    }
}
