package com.seastreams.tv.ui.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PosterGridTest {

    @Test
    fun `bottom row starts at the last multiple of the column count`() {
        assertEquals(0, lastRowStart(0, POSTER_GRID_COLUMNS))
        assertEquals(0, lastRowStart(3, POSTER_GRID_COLUMNS))
        assertEquals(0, lastRowStart(5, POSTER_GRID_COLUMNS))
        assertEquals(5, lastRowStart(6, POSTER_GRID_COLUMNS))
        assertEquals(95, lastRowStart(100, POSTER_GRID_COLUMNS))
        assertEquals(95, lastRowStart(97, POSTER_GRID_COLUMNS))
    }

    @Test
    fun `poster grids are five columns of 2 by 3 posters`() {
        assertEquals(5, POSTER_GRID_COLUMNS)
        assertEquals(2f / 3f, POSTER_ASPECT_RATIO)
    }

    @Test
    fun `holding Down from column 3 stays in column 3 and never snaps to column 1`() {
        val count = 100
        val path = generateSequence(2) { gridDownTarget(it, count, POSTER_GRID_COLUMNS) }.toList()
        assertEquals((2 until count step 5).toList(), path)
        path.forEach { index -> assertEquals(2, index % POSTER_GRID_COLUMNS, "index=$index") }
        // The walk stops on the bottom row (index 97), where edge-stop-then-wrap takes over.
        assertNull(gridDownTarget(97, count, POSTER_GRID_COLUMNS))
    }

    @Test
    fun `Down into a short bottom row lands on the nearest poster instead of column 1`() {
        // 12 items: bottom row holds indices 10 and 11 (columns 1 and 2).
        assertEquals(7 + 5 - 1, gridDownTarget(7, 12, POSTER_GRID_COLUMNS)) // column 3 -> last poster (11)
        assertEquals(11, gridDownTarget(9, 12, POSTER_GRID_COLUMNS))
        assertEquals(11, gridDownTarget(6, 12, POSTER_GRID_COLUMNS))
        assertEquals(10, gridDownTarget(5, 12, POSTER_GRID_COLUMNS))
        assertNull(gridDownTarget(10, 12, POSTER_GRID_COLUMNS))
        assertNull(gridDownTarget(11, 12, POSTER_GRID_COLUMNS))
    }

    @Test
    fun `Up stays in the same column and leaves the grid from the top row`() {
        assertEquals(7, gridUpTarget(12, POSTER_GRID_COLUMNS))
        assertEquals(2, gridUpTarget(7, POSTER_GRID_COLUMNS))
        assertNull(gridUpTarget(2, POSTER_GRID_COLUMNS))
        assertNull(gridUpTarget(0, POSTER_GRID_COLUMNS))
    }

    @Test
    fun `a single-row grid has no Down target`() {
        (0 until 4).forEach { assertNull(gridDownTarget(it, 4, POSTER_GRID_COLUMNS)) }
        assertNull(gridDownTarget(0, 0, POSTER_GRID_COLUMNS))
    }

    @Test
    fun `peek spec keeps a strip of the neighbouring rows visible`() {
        val spec = PeekBringIntoViewSpec(peekPx = 40f)
        // Fully visible with room for the peek: no scroll.
        assertEquals(0f, spec.calculateScrollDistance(offset = 100f, size = 400f, containerSize = 1000f))
        // Bottom edge too close to the viewport end: scroll so 40px of the next row shows.
        assertEquals(40f, spec.calculateScrollDistance(offset = 600f, size = 400f, containerSize = 1000f))
        // Top edge above the peek line: scroll back so 40px of the previous row shows.
        assertEquals(-60f, spec.calculateScrollDistance(offset = -20f, size = 400f, containerSize = 1000f))
        // Taller than the viewport minus both peeks: align its top under the peek.
        assertEquals(60f, spec.calculateScrollDistance(offset = 100f, size = 950f, containerSize = 1000f))
    }

    @Test
    fun `held grid steps are paced at the grid cadence`() {
        assertEquals(250L, POSTER_GRID_REPEAT_INTERVAL_MS)
    }
}
