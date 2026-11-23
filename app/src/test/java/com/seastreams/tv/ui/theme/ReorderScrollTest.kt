package com.seastreams.tv.ui.theme

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Drives the real [followMove] / [revealItem] against [ModelList], a LazyColumn model: uniform rows with spacing,
 * content padding inside the viewport, only rows touching the viewport laid out, the first visible row kept in place
 * by key when the order changes, and a new order measured only on the next frame.
 */
class ReorderScrollTest {

    @Test
    fun `moving a row down past the bottom of a long list keeps it fully visible and focused`() = runTest {
        val list = ModelList(keys = (0 until 30).map { "row$it" })
        val moved = "row0"
        repeat(29) { step ->
            val focused = list.press(moved, offset = 1)
            val index = list.keys.indexOf(moved)
            assertEquals(step + 1, index)
            assertTrue(list.fullyVisible(index), "row at $index is clipped: ${list.span(index)} vs ${list.contentEnd}")
            assertEquals(if (index == 29) MoveButton.UP else MoveButton.DOWN, focused)
        }
        // A row pushed below the viewport ends on the bottom edge (smallest scroll), never jumps to the top.
        assertEquals(list.contentEnd, list.span(29)?.let { it.offset + it.size })
    }

    @Test
    fun `a row moved down from the last visible slot lands flush with the content end, outside the padding`() = runTest {
        val list = ModelList(keys = (0 until 30).map { "row$it" })
        val lastFullyVisible = (0 until 30).last { list.fullyVisible(it) }
        val focused = list.press("row$lastFullyVisible", offset = 1)
        val span = list.span(lastFullyVisible + 1)
        assertEquals(MoveButton.DOWN, focused)
        assertEquals(list.contentEnd, span?.let { it.offset + it.size })
    }

    @Test
    fun `without bottom padding a row moved out of the layout is brought back to the bottom edge, not the top`() = runTest {
        val list = ModelList(keys = (0 until 30).map { "row$it" }, padAfter = 0)
        // Scroll so the 6th row ends exactly on the content end: one move down takes it out of the layout.
        list.scrollBy((6 * 173 - 21 - list.contentEnd).toFloat())
        assertEquals(list.contentEnd, list.span(5)?.let { it.offset + it.size })
        repeat(10) {
            val index = list.keys.indexOf("row5") + 1
            assertEquals(MoveButton.DOWN, list.press("row5", offset = 1))
            assertEquals(list.contentEnd, list.span(index)?.let { it.offset + it.size })
        }
    }

    @Test
    fun `moving a row up from the bottom keeps it visible and switches to Move down at the top`() = runTest {
        val list = ModelList(keys = (0 until 30).map { "row$it" })
        list.scrollToItem(29)
        repeat(29) {
            val focused = list.press("row29", offset = -1)
            val index = list.keys.indexOf("row29")
            assertTrue(list.fullyVisible(index))
            assertEquals(if (index == 0) MoveButton.DOWN else MoveButton.UP, focused)
        }
    }

    @Test
    fun `the padding strip does not count as visible`() {
        val layout = ListLayout(listOf(ItemSpan(4, offset = 380, size = 100)), contentStart = 0, contentEnd = 450)
        assertEquals(Reveal.By(30f), reveal(4, layout))
        assertFalse(isFullyVisible(4, layout))
        assertEquals(Reveal.ToItem(9, alignEnd = true), reveal(9, layout))
        assertEquals(Reveal.ToItem(1, alignEnd = false), reveal(1, layout))
    }

    @Test
    fun `the order counts as committed only once the moved row left its old index`() {
        val stale = ListLayout(listOf(ItemSpan(3, 0, 100, "a"), ItemSpan(4, 108, 100, "b")), 0, 450)
        assertFalse(orderCommitted(stale, index = 4, key = "a"))
        assertTrue(orderCommitted(stale, index = 3, key = "a"))
        assertTrue(orderCommitted(stale, index = 9, key = "z"))
    }
}

/** A LazyColumn model; see [ReorderScrollTest]. Offsets are relative to the content start, like `LazyListItemInfo`. */
private class ModelList(
    keys: List<String>,
    private val rowSize: Int = 152,
    private val spacing: Int = 21,
    private val viewport: Int = 900,
    private val padBefore: Int = 10,
    private val padAfter: Int = 105,
) : RevealList {
    var keys: List<String> = keys
        private set
    private var measuredKeys = keys
    private var scroll = 0
    val contentEnd get() = viewport - padBefore - padAfter
    private val stride get() = rowSize + spacing

    override val layout: ListLayout
        get() = ListLayout(
            items = measuredKeys.indices.mapNotNull { i ->
                val top = i * stride - scroll
                if (top + rowSize <= -padBefore || top >= contentEnd + padAfter) null else ItemSpan(i, top, rowSize, measuredKeys[i])
            },
            contentStart = 0,
            contentEnd = contentEnd,
        )

    override suspend fun scrollBy(pixels: Float) {
        scroll = (scroll + pixels.toInt()).coerceIn(0, maxScroll())
    }

    override suspend fun scrollToItem(index: Int) {
        scroll = (index * stride).coerceIn(0, maxScroll())
    }

    private fun maxScroll() = (keys.size * stride - spacing - contentEnd).coerceAtLeast(0)

    /** A frame: the saved order gets measured, keeping the first visible row in place (LazyList's key anchoring). */
    private fun frame() {
        if (measuredKeys == keys) return
        val first = measuredKeys.indices.first { it * stride + rowSize > scroll }
        val within = scroll - first * stride
        val anchor = measuredKeys[first]
        measuredKeys = keys
        scroll = (keys.indexOf(anchor) * stride + within).coerceIn(0, maxScroll())
    }

    fun span(index: Int): ItemSpan? = layout.items.firstOrNull { it.index == index }

    fun fullyVisible(index: Int) = isFullyVisible(index, layout)

    /** Presses Move up / Move down on [key]: saves the new order, follows it, and returns the focused button. */
    suspend fun press(key: String, offset: Int): MoveButton? {
        val from = keys.indexOf(key)
        val to = (from + offset).coerceIn(0, keys.lastIndex)
        keys = keys.toMutableList().apply { add(to, removeAt(from)) }
        var focused: MoveButton? = null
        followMove(this, PendingMove(key, offset, to), keys, to, key, awaitFrame = ::frame) { button ->
            focused = button
            span(to) != null
        }
        return focused
    }
}
