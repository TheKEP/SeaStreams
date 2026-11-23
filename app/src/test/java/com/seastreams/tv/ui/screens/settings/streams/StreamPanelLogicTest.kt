package com.seastreams.tv.ui.screens.settings.streams

import com.seastreams.tv.domain.model.StreamDisplay
import com.seastreams.tv.domain.model.StreamSource
import com.seastreams.tv.domain.streams.AttributeFilter
import com.seastreams.tv.domain.streams.FilterAttribute
import com.seastreams.tv.domain.streams.FilterList
import com.seastreams.tv.domain.streams.FormatterId
import com.seastreams.tv.domain.streams.SortCriterion
import com.seastreams.tv.domain.streams.SortDirection
import com.seastreams.tv.domain.streams.SortKey
import com.seastreams.tv.domain.streams.SortScope
import com.seastreams.tv.domain.streams.StreamFilters
import com.seastreams.tv.domain.streams.StreamFormatterChoice
import com.seastreams.tv.domain.streams.StreamSort
import com.seastreams.tv.ui.screens.playback.streamCardText
import com.seastreams.tv.ui.screens.settings.PanelItem
import com.seastreams.tv.ui.screens.settings.SettingsPanel
import com.seastreams.tv.ui.screens.settings.parent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class StreamPanelLogicTest {

    @Test
    fun `multi-choice rows put chosen values first only when ordered`() {
        val options = listOf("2160p", "1080p", "720p")
        assertEquals(
            listOf(MultiChoiceRow("720p", true), MultiChoiceRow("2160p", true), MultiChoiceRow("1080p", false)),
            multiChoiceRows(options, listOf("720p", "2160p"), ordered = true),
        )
        assertEquals(
            listOf(MultiChoiceRow("2160p", true), MultiChoiceRow("1080p", false), MultiChoiceRow("720p", true)),
            multiChoiceRows(options, listOf("720p", "2160p"), ordered = false),
        )
        assertEquals(listOf("720p", "2160p", "1080p"), toggled(listOf("720p", "2160p"), "1080p"))
        assertEquals(listOf("2160p"), toggled(listOf("720p", "2160p"), "720p"))
        assertEquals(listOf("2160p", "720p"), moved(listOf("720p", "2160p"), "2160p", -1))
        assertEquals(listOf("720p", "2160p"), moved(listOf("720p", "2160p"), "720p", -1))
    }

    @Test
    fun `sort rows, moves and direction flips`() {
        val criteria = listOf(SortCriterion(SortKey.SIZE), SortCriterion(SortKey.RESOLUTION))
        assertEquals(listOf(SortKey.SIZE, SortKey.RESOLUTION), sortRows(criteria).take(2).map { it.key })
        assertNull(sortRows(criteria)[2].criterion)
        assertEquals(SortKey.entries.size, sortRows(criteria).size)
        assertEquals(listOf(SortKey.RESOLUTION, SortKey.SIZE), movedCriterion(criteria, SortKey.RESOLUTION, -1).map { it.key })
        assertEquals(SortDirection.ASC, flipped(criteria, SortKey.SIZE).first().direction)
        assertEquals(criteria + SortCriterion(SortKey.SEEDERS), toggledCriterion(criteria, SortKey.SEEDERS))
        assertEquals("Movies — empty, Global applies", scopeLabel(SortScope.MOVIES, StreamSort(global = criteria)))
        assertEquals("Off — addon order", sortSummary(StreamSort()))
        assertEquals("Global: Size, Resolution", sortSummary(StreamSort(global = criteria)))
    }

    @Test
    fun `result limits step through Off and the presets, both ways`() {
        assertEquals(1, stepLimit(null, 1))
        assertEquals(null, stepLimit(1, -1))
        assertEquals(50, stepLimit(null, -1))
        assertEquals(null, stepLimit(50, 1))
        assertEquals(10, stepLimit(7, 1))
        assertEquals(listOf(30, 40, 50), listOf(stepLimit(20, 1), stepLimit(30, 1), stepLimit(40, 1)))
        assertEquals(listOf(40, 30, 20), listOf(stepLimit(50, -1), stepLimit(40, -1), stepLimit(30, -1)))
    }

    @Test
    fun `Back walks values to group to filters to the list`() {
        val values = SettingsPanel.Stream.FilterValues(FilterAttribute.RESOLUTION, FilterList.PREFERRED)
        assertEquals(SettingsPanel.Stream.FilterGroup(FilterAttribute.RESOLUTION), values.parent())
        assertEquals(SettingsPanel.Stream.Filters, values.parent().parent())
        assertEquals(SettingsPanel.Main, SettingsPanel.Stream.Filters.parent())
        assertEquals(SettingsPanel.Main, SettingsPanel.Stream.Formatter.parent())
    }

    @Test
    fun `filter panel rows and summaries`() {
        val filters = StreamFilters(resolutions = AttributeFilter(excluded = listOf("480p", "360p"), preferred = listOf("2160p")))
        assertEquals("1 filter on", filtersSummary(filters))
        assertEquals("Off — every stream is shown", filtersSummary(StreamFilters()))
        val rows = streamFilterItems(filters, {}, {}, {}).filterIsInstance<PanelItem.Row>()
        assertEquals("Excluded 2 · Preferred 1", rows.first { it.key == attributeKey(FilterAttribute.RESOLUTION) }.value)
        val group = filterGroupItems(FilterAttribute.QUALITY, AttributeFilter(), {}).filterIsInstance<PanelItem.Row>()
        assertEquals(listOf("Required qualities", "Excluded qualities", "Included qualities", "Preferred qualities"), group.map { it.title })
    }

    @Test
    fun `stream cards show the formatter output and fall back to the raw text`() {
        val raw = StreamSource("u", "Torrentio", "x", "Torrentio · 4k", "File.mkv\n💾 2 GB", emptyMap())
        assertEquals("Torrentio · 4k", streamCardText(raw).title)
        val formatted = raw.copy(display = StreamDisplay("🔥 4K", "line one\nline two"))
        assertEquals("🔥 4K", streamCardText(formatted).title)
        assertEquals(listOf("line one", "line two"), streamCardText(formatted).detailLines)
        assertEquals("Custom (edit in the Web Portal)", formatterLabel(FormatterId.CUSTOM, StreamFormatterChoice()))
        assertEquals("🔥 4K 🎬 Movie Title (2023)", previewDisplay(StreamFormatterChoice(FormatterId.CUSTOM), 0)?.name)
    }
}
