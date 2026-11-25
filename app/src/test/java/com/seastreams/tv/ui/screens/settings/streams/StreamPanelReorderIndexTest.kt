package com.seastreams.tv.ui.screens.settings.streams

import com.seastreams.tv.domain.streams.SortCriterion
import com.seastreams.tv.domain.streams.SortKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Where a toggled row lands, which `ReorderFocus.onReorder` waits for before scrolling to it and focusing it
 * (Stream filters' Preferred lists, Stream sorting).
 */
class StreamPanelReorderIndexTest {

    private val options = listOf("2160p", "1440p", "1080p", "720p", "576p", "480p", "360p", "240p", "144p", "Unknown")

    @Test
    fun `a value chosen into a Preferred list jumps up to the end of the chosen block`() {
        val updated = toggled(listOf("2160p", "1080p"), "480p")
        assertEquals(2, rowIndexAfter(options, updated, ordered = true, value = "480p"))
    }

    @Test
    fun `a value dropped from a Preferred list returns to its option position among the rest`() {
        val updated = toggled(listOf("2160p", "1080p", "480p"), "2160p")
        // Rows: 1080p, 480p (chosen), then 2160p, 1440p, … — 2160p leads the unchosen block.
        assertEquals(2, rowIndexAfter(options, updated, ordered = true, value = "2160p"))
    }

    @Test
    fun `unordered lists never move a row`() {
        val updated = toggled(emptyList(), "480p")
        assertEquals(options.indexOf("480p"), rowIndexAfter(options, updated, ordered = false, value = "480p"))
    }

    @Test
    fun `an added sort criterion joins the end of the list in use, a removed one goes back among the unused`() {
        val criteria = listOf(SortCriterion(SortKey.RESOLUTION), SortCriterion(SortKey.SIZE))
        assertEquals(2, sortRowIndexAfter(toggledCriterion(criteria, SortKey.SEEDERS), SortKey.SEEDERS))
        val removed = toggledCriterion(criteria, SortKey.RESOLUTION)
        // SIZE stays in use; unused keys follow in SortKey order, where RESOLUTION is the second key.
        assertEquals(1 + SortKey.entries.filter { it != SortKey.SIZE }.indexOf(SortKey.RESOLUTION), sortRowIndexAfter(removed, SortKey.RESOLUTION))
    }
}
