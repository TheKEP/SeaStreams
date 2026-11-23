package com.seastreams.tv.ui.screens.settings

import com.seastreams.tv.ui.components.SeaIcons
import com.seastreams.tv.ui.screens.settings.streams.STREAM_FORMATTER_ROW_KEY
import androidx.compose.ui.input.key.Key
import com.seastreams.tv.core.datastore.AppSettings
import com.seastreams.tv.core.datastore.PlayerSettings
import com.seastreams.tv.ui.theme.EdgeKeyResult
import com.seastreams.tv.ui.theme.edgeKeyResult
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Test

class SettingsCategoryTest {

    @Test
    fun `the tab bar reads Home, UI, Playback, Audio, Subtitles, Sources, Other`() {
        assertEquals(listOf("Home", "UI", "Playback", "Audio", "Subtitles", "Sources", "Other"), SettingsCategory.entries.map { it.label })
    }

    @Test
    fun `all seven tabs fit on one row of a 960 dp TV inside the page gutters`() {
        // Upper bound for a bold titleMedium (16 sp) label: 0.7 em per character, plus each tab's 22 dp side padding.
        val tabs = SettingsCategory.entries.map { 16.dp * 0.7f * it.label.length + 22.dp * 2 }
        val gaps = 4.dp * (tabs.size - 1)
        val container = 6.dp * 2
        val total = tabs.fold(gaps + container) { sum, width -> sum + width }
        val available = 960.dp - 56.dp * 2
        assertTrue(total <= available, "tab bar needs " + total + " of " + available)
    }

    @Test
    fun `fresh Left on Home wraps to Other and fresh Right on Other wraps to Home`() {
        val last = SettingsCategory.OTHER.ordinal
        assertEquals(setOf(Key.DirectionLeft), categoryEdgeKeys(SettingsCategory.HOME.ordinal))
        assertEquals(setOf(Key.DirectionRight), categoryEdgeKeys(last))
        assertEquals(last, categoryWrapTarget(SettingsCategory.HOME.ordinal, Key.DirectionLeft))
        assertEquals(0, categoryWrapTarget(last, Key.DirectionRight))
        assertEquals(EdgeKeyResult.WRAP, edgeKeyResult(matchesKey = true, isKeyDown = true, repeatCount = 0))
    }

    @Test
    fun `held Left or Right stops on the edge tab`() {
        listOf(1, 2, 12).forEach { repeats ->
            assertEquals(EdgeKeyResult.HOLD, edgeKeyResult(matchesKey = true, isKeyDown = true, repeatCount = repeats), "repeat $repeats")
        }
    }

    @Test
    fun `middle tabs own no wrap keys and inward moves never wrap`() {
        (1 until SettingsCategory.OTHER.ordinal).forEach { index ->
            assertEquals(emptySet<Key>(), categoryEdgeKeys(index), "tab $index")
            assertNull(categoryWrapTarget(index, Key.DirectionLeft))
            assertNull(categoryWrapTarget(index, Key.DirectionRight))
        }
        assertNull(categoryWrapTarget(SettingsCategory.HOME.ordinal, Key.DirectionRight))
        assertNull(categoryWrapTarget(SettingsCategory.OTHER.ordinal, Key.DirectionLeft))
    }

    @Test
    fun `every category wraps bottom-to-top only and Up on the first row goes to the tab bar`() {
        listOf(PlayerSettings(), PlayerSettings(autoStreamFailover = false, autoPlayTrailers = false)).forEach { player ->
            SettingsCategory.entries.forEach { category ->
                val items = items(category, player)
                val ordinals = settingsEntryOrdinals(items)
                val lastOrdinal = ordinals.max()
                val edgesByRow = ordinals.filter { it >= 0 }.map { settingsRowEdges(it, lastOrdinal) }

                assertEquals(mapOf(Key.DirectionUp to SettingsRowEdge.TO_TAB_BAR), edgesByRow.first(), "${category.label} first row")
                assertEquals(mapOf(Key.DirectionDown to SettingsRowEdge.WRAP_TO_FIRST), edgesByRow.last(), "${category.label} last row")
                // Nothing ever wraps Up to the bottom.
                edgesByRow.forEach { assertFalse(it[Key.DirectionUp] == SettingsRowEdge.WRAP_TO_FIRST, category.label) }
                edgesByRow.drop(1).dropLast(1).forEach { assertEquals(emptyMap<Key, SettingsRowEdge>(), it, category.label) }
                assertEquals(items.last { it is SettingsItem.Entry }.key, lastEntryKey(items))
            }
        }
    }

    @Test
    fun `the bottom edge follows the failover and trailer sub-settings`() {
        fun lastOrdinalKey(category: SettingsCategory, player: PlayerSettings): String {
            val items = items(category, player)
            val ordinals = settingsEntryOrdinals(items)
            val lastOrdinal = ordinals.max()
            return items[ordinals.indexOfFirst { settingsRowEdges(it, lastOrdinal)[Key.DirectionDown] != null }].key
        }
        // UI ends with the stream rows whatever the trailer sub-settings do.
        assertEquals(STREAM_FORMATTER_ROW_KEY, lastOrdinalKey(SettingsCategory.UI, PlayerSettings()))
        assertEquals(STREAM_FORMATTER_ROW_KEY, lastOrdinalKey(SettingsCategory.UI, PlayerSettings(autoPlayTrailers = false)))
        assertEquals(HDR_DIM_ROW_KEY, lastOrdinalKey(SettingsCategory.PLAYBACK, PlayerSettings(autoStreamFailover = false)))
    }

    @Test
    fun `section subheadings are skipped by focus and split the list into cards`() {
        val items = listOf(
            SettingsItem.Section("s1", "Usenet"),
            entry("a", SeaIcons.Addons, "A", null, RowTrailing.None) {},
            entry("b", SeaIcons.Addons, "B", null, RowTrailing.None) {},
            SettingsItem.Section("s2", "Debrid library"),
            entry("c", SeaIcons.Addons, "C", null, RowTrailing.None) {},
        )
        assertEquals(listOf(-1, 0, 1, -1, 2), settingsEntryOrdinals(items).toList())
        assertEquals("a", firstEntryKey(items))
        assertEquals("c", lastEntryKey(items))
        assertEquals(SectionSegment(first = true, last = false), sectionSegment(items, 1))
        assertEquals(SectionSegment(first = false, last = true), sectionSegment(items, 2))
        assertEquals(SectionSegment(first = true, last = true), sectionSegment(items, 4))
        // One-way wrap across the whole tab: Down on `c` goes back to `a`, Up on `a` to the tab bar.
        assertEquals(mapOf(Key.DirectionUp to SettingsRowEdge.TO_TAB_BAR), settingsRowEdges(0, 2))
        assertEquals(mapOf(Key.DirectionDown to SettingsRowEdge.WRAP_TO_FIRST), settingsRowEdges(2, 2))
    }

    @Test
    fun `a single row sends Up to the tab bar and keeps Down on itself`() {
        assertEquals(
            mapOf(Key.DirectionUp to SettingsRowEdge.TO_TAB_BAR, Key.DirectionDown to SettingsRowEdge.WRAP_TO_FIRST),
            settingsRowEdges(0, 0),
        )
        assertEquals(emptyMap<Key, SettingsRowEdge>(), settingsRowEdges(3, 2))
    }

    @Test
    fun `fresh Up on row 1 leaves for the tab bar while held Up stops on it`() {
        assertEquals(EdgeKeyResult.WRAP, edgeKeyResult(matchesKey = true, isKeyDown = true, repeatCount = 0))
        assertEquals(EdgeKeyResult.HOLD, edgeKeyResult(matchesKey = true, isKeyDown = true, repeatCount = 3))
    }

    @Test
    fun `Back closes sub-panels first, then leaves a row for the active tab, then exits from the bar`() {
        assertEquals(SettingsBackAction.CLOSE_PANEL, settingsBackAction(panelOpen = true, swatchesOpen = false, focusInList = true))
        assertEquals(SettingsBackAction.REVERT_SWATCHES, settingsBackAction(panelOpen = false, swatchesOpen = true, focusInList = true))
        assertEquals(SettingsBackAction.FOCUS_ACTIVE_PILL, settingsBackAction(panelOpen = false, swatchesOpen = false, focusInList = true))
        assertEquals(SettingsBackAction.EXIT, settingsBackAction(panelOpen = false, swatchesOpen = false, focusInList = false))
    }

    private fun items(category: SettingsCategory, player: PlayerSettings) = settingsItems(
        category = category,
        state = SettingsUiState(settings = AppSettings(player = player)),
        swatchesOpen = false,
        clearSecondsLeft = null,
        cacheBytes = 0,
        cb = SettingsCallbacks({}, {}, {}, mockk(relaxed = true), {}, {}, {}, {}),
    )
}
