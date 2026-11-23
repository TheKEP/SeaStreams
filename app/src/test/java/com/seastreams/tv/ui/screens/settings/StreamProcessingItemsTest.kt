package com.seastreams.tv.ui.screens.settings

import com.seastreams.tv.domain.streams.StreamProcessingSettings
import com.seastreams.tv.ui.screens.settings.streams.STREAM_FILTERING_TOGGLE_KEY
import com.seastreams.tv.ui.screens.settings.streams.STREAM_FILTERS_ROW_KEY
import com.seastreams.tv.ui.screens.settings.streams.STREAM_FORMATTER_ROW_KEY
import com.seastreams.tv.ui.screens.settings.streams.STREAM_FORMATTING_TOGGLE_KEY
import com.seastreams.tv.ui.screens.settings.streams.STREAM_SORTING_TOGGLE_KEY
import com.seastreams.tv.ui.screens.settings.streams.STREAM_SORT_ROW_KEY
import com.seastreams.tv.ui.screens.settings.streams.StreamProcessingActions
import com.seastreams.tv.ui.screens.settings.streams.filteringDescription
import com.seastreams.tv.ui.screens.settings.streams.formattingDescription
import com.seastreams.tv.ui.screens.settings.streams.sortingDescription
import com.seastreams.tv.ui.screens.settings.streams.streamReturnRow
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `Settings → UI` stream processing master toggles and their indented panel rows (PRD §11, §13). */
class StreamProcessingItemsTest {

    private val toggles = listOf(STREAM_FILTERING_TOGGLE_KEY, STREAM_SORTING_TOGGLE_KEY, STREAM_FORMATTING_TOGGLE_KEY)
    private val panelRows = listOf(STREAM_FILTERS_ROW_KEY, STREAM_SORT_ROW_KEY, STREAM_FORMATTER_ROW_KEY)

    @Test
    fun `each toggle defaults on with its panel row indented directly beneath it`() {
        val items = ui(StreamProcessingSettings())
        val keys = items.map { it.key }
        toggles.zip(panelRows).forEach { (toggle, row) ->
            assertEquals(row, keys[keys.indexOf(toggle) + 1])
            assertEquals(RowTrailing.Toggle(true), items.entry(toggle).trailing)
            assertFalse(items.entry(toggle).indented)
            assertTrue(items.entry(row).indented)
            assertEquals(RowTrailing.Chevron, items.entry(row).trailing)
        }
    }

    @Test
    fun `switching a stage off hides only its own panel row`() {
        val cases = listOf(
            StreamProcessingSettings(filteringEnabled = false) to STREAM_FILTERS_ROW_KEY,
            StreamProcessingSettings(sortingEnabled = false) to STREAM_SORT_ROW_KEY,
            StreamProcessingSettings(formattingEnabled = false) to STREAM_FORMATTER_ROW_KEY,
        )
        val all = ui(StreamProcessingSettings()).map { it.key }
        cases.forEach { (settings, hidden) ->
            val keys = ui(settings).map { it.key }
            assertEquals(all - hidden, keys, hidden)
        }
        val none = ui(StreamProcessingSettings(filteringEnabled = false, sortingEnabled = false, formattingEnabled = false))
        assertEquals(toggles, none.map { it.key }.takeLast(3))
        assertTrue(toggles.all { none.entry(it).trailing == RowTrailing.Toggle(false) })
    }

    @Test
    fun `the one-way bottom wrap edge follows the formatter row's visibility`() {
        val on = ui(StreamProcessingSettings())
        assertEquals(STREAM_FORMATTER_ROW_KEY, lastEntryKey(on))
        assertEquals(on.count { it is SettingsItem.Entry } - 1, settingsEntryOrdinals(on).max())

        val off = ui(StreamProcessingSettings(formattingEnabled = false))
        assertEquals(STREAM_FORMATTING_TOGGLE_KEY, lastEntryKey(off))
        assertEquals(on.count { it is SettingsItem.Entry } - 2, settingsEntryOrdinals(off).max())
        // The top edge never moves.
        assertEquals(BACKGROUND_ROW_KEY, firstEntryKey(off))
        // Hiding a middle sub-row leaves the bottom edge alone.
        assertEquals(STREAM_FORMATTER_ROW_KEY, lastEntryKey(ui(StreamProcessingSettings(filteringEnabled = false, sortingEnabled = false))))
    }

    @Test
    fun `a toggle click sends the opposite of its current value`() {
        val calls = mutableListOf<Pair<String, Boolean>>()
        val actions = StreamProcessingActions(
            setFilteringEnabled = { calls += "filtering" to it },
            setSortingEnabled = { calls += "sorting" to it },
            setFormattingEnabled = { calls += "formatting" to it },
        )
        val on = ui(StreamProcessingSettings(), actions)
        toggles.forEach { on.entry(it).onClick() }
        val off = ui(StreamProcessingSettings(filteringEnabled = false, sortingEnabled = false, formattingEnabled = false), actions)
        toggles.forEach { off.entry(it).onClick() }
        assertEquals(
            listOf("filtering" to false, "sorting" to false, "formatting" to false, "filtering" to true, "sorting" to true, "formatting" to true),
            calls,
        )
    }

    @Test
    fun `subtitles say whether each stage is active or bypassed with its settings kept`() {
        val on = ui(StreamProcessingSettings())
        assertEquals("On: your stream filters are applied", on.entry(STREAM_FILTERING_TOGGLE_KEY).value)
        assertEquals("On: streams are sorted by your criteria", on.entry(STREAM_SORTING_TOGGLE_KEY).value)
        assertEquals("On: streams use your chosen formatter", on.entry(STREAM_FORMATTING_TOGGLE_KEY).value)
        assertEquals("Off: every stream is shown — your filters are kept", filteringDescription(false))
        assertEquals("Off: addon order — your sort criteria are kept", sortingDescription(false))
        assertEquals("Off: the addon's own text — your formatter is kept", formattingDescription(false))
        assertEquals(
            listOf("Enable stream filtering", "Enable stream sorting", "Enable stream formatting"),
            toggles.map { on.entry(it).title },
        )
    }

    @Test
    fun `Back from a panel whose row was hidden meanwhile lands on its toggle`() {
        val on = StreamProcessingSettings()
        panelRows.forEach { assertEquals(it, streamReturnRow(it, on)) }
        val off = StreamProcessingSettings(filteringEnabled = false, sortingEnabled = false, formattingEnabled = false)
        assertEquals(toggles, panelRows.map { streamReturnRow(it, off) })
        assertEquals(BACKGROUND_ROW_KEY, streamReturnRow(BACKGROUND_ROW_KEY, off))
    }

    private fun List<SettingsItem>.entry(key: String): SettingsItem.Entry = first { it.key == key } as SettingsItem.Entry

    private fun ui(streams: StreamProcessingSettings, actions: StreamProcessingActions = StreamProcessingActions()) = settingsItems(
        category = SettingsCategory.UI,
        state = SettingsUiState(),
        swatchesOpen = false,
        clearSecondsLeft = null,
        cacheBytes = 0,
        cb = SettingsCallbacks({}, {}, {}, mockk(relaxed = true), {}, {}, {}, {}, streams = actions),
        streams = streams,
    )
}
