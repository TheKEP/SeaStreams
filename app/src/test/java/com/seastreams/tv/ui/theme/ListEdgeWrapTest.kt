package com.seastreams.tv.ui.theme

import androidx.compose.ui.input.key.Key
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Two-way §1 edge-stop-then-wrap on vertical lists (stream modal, player `Streams` overlay, More
 * Episodes seasons and episodes): which edge keys each item owns, and what a fresh or held press does.
 */
class ListEdgeWrapTest {

    @Test
    fun `the first item owns Up and the last owns Down`() {
        assertEquals(setOf(Key.DirectionUp), verticalEdgeKeys(index = 0, lastIndex = 4))
        assertEquals(setOf(Key.DirectionDown), verticalEdgeKeys(index = 4, lastIndex = 4))
    }

    @Test
    fun `middle items own no edge key`() {
        (1..3).forEach { assertEquals(emptySet<Key>(), verticalEdgeKeys(index = it, lastIndex = 4)) }
    }

    @Test
    fun `a single item owns both directions so focus stays put`() {
        assertEquals(setOf(Key.DirectionUp, Key.DirectionDown), verticalEdgeKeys(index = 0, lastIndex = 0))
    }

    @Test
    fun `an empty list or an out-of-range index owns nothing`() {
        assertEquals(emptySet<Key>(), verticalEdgeKeys(index = 0, lastIndex = -1))
        assertEquals(emptySet<Key>(), verticalEdgeKeys(index = 5, lastIndex = 4))
    }

    @Test
    fun `fresh Up on the first item wraps to the bottom, held Up stops there`() {
        val owns = Key.DirectionUp in verticalEdgeKeys(index = 0, lastIndex = 4)
        assertEquals(EdgeKeyResult.WRAP, edgeKeyResult(matchesKey = owns, isKeyDown = true, repeatCount = 0))
        assertEquals(EdgeKeyResult.HOLD, edgeKeyResult(matchesKey = owns, isKeyDown = true, repeatCount = 3))
    }

    @Test
    fun `fresh Down on the last item wraps to the top, held Down stops there`() {
        val owns = Key.DirectionDown in verticalEdgeKeys(index = 4, lastIndex = 4)
        assertEquals(EdgeKeyResult.WRAP, edgeKeyResult(matchesKey = owns, isKeyDown = true, repeatCount = 0))
        assertEquals(EdgeKeyResult.HOLD, edgeKeyResult(matchesKey = owns, isKeyDown = true, repeatCount = 1))
    }

    @Test
    fun `inward presses on an edge item are ordinary focus moves`() {
        val downOnFirst = Key.DirectionDown in verticalEdgeKeys(index = 0, lastIndex = 4)
        val upOnLast = Key.DirectionUp in verticalEdgeKeys(index = 4, lastIndex = 4)
        assertEquals(EdgeKeyResult.PASS, edgeKeyResult(matchesKey = downOnFirst, isKeyDown = true, repeatCount = 0))
        assertEquals(EdgeKeyResult.PASS, edgeKeyResult(matchesKey = upOnLast, isKeyDown = true, repeatCount = 0))
    }
}
