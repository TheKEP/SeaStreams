package com.seastreams.tv.ui.screens.settings

import androidx.compose.ui.input.key.Key
import com.seastreams.tv.ui.theme.EdgeKeyResult
import com.seastreams.tv.ui.theme.edgeKeyResult
import com.seastreams.tv.ui.theme.verticalEdgeKeys
import com.seastreams.tv.core.datastore.SubtitleSettings
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SettingsItemsTest {

    @Test
    fun `cache size reads Calculating while it is measured or cleared`() {
        assertEquals("Calculating…", cacheSizeLabel(null))
    }

    @Test
    fun `an empty or near-empty cache reads 0 MB`() {
        assertEquals("0 MB", cacheSizeLabel(0))
        assertEquals("0 MB", cacheSizeLabel(12_000))
    }

    @Test
    fun `a used cache reads its size in whole megabytes`() {
        assertEquals("1 MB used", cacheSizeLabel(MB))
        assertEquals("148 MB used", cacheSizeLabel(148 * MB + MB / 3))
        assertEquals("250 MB used", cacheSizeLabel(250 * MB))
    }

    @Test
    fun `Clear cache sits under Storage as the last row`() {
        var cleared = 0
        val items = buildItems(onClearCache = { cleared++ })

        val last = items.last() as SettingsItem.Entry
        assertEquals(CLEAR_CACHE_ROW_KEY, last.key)
        assertEquals("Clear cache", last.title)
        assertEquals("148 MB used", last.value)
        assertEquals(RowTrailing.None, last.trailing)
        assertEquals("Storage", (items[items.size - 2] as SettingsItem.Header).title)

        last.onClick()
        assertEquals(1, cleared)
    }

    @Test
    fun `fresh Up on the first row wraps to Clear cache`() {
        val items = buildItems()
        val edges = Edges(items)

        assertEquals("addons", items[edges.firstIndex].key)
        assertTrue(Key.DirectionUp in edges.keysAt(edges.firstIndex))
        assertEquals(EdgeKeyResult.WRAP, edgeKeyResult(matchesKey = true, isKeyDown = true, repeatCount = 0))
        // toLast scrolls to the list's last item and focuses it: the Clear cache row.
        assertEquals(CLEAR_CACHE_ROW_KEY, items.last().key)
        assertEquals(edges.lastIndex, items.lastIndex)
    }

    @Test
    fun `fresh Down on Clear cache wraps to the first row with its header in view`() {
        val items = buildItems()
        val edges = Edges(items)

        assertEquals(CLEAR_CACHE_ROW_KEY, items[edges.lastIndex].key)
        assertTrue(Key.DirectionDown in edges.keysAt(edges.lastIndex))
        assertEquals(EdgeKeyResult.WRAP, edgeKeyResult(matchesKey = true, isKeyDown = true, repeatCount = 0))
        // toFirst scrolls to item 0, the Addons & Portal header, then focuses the first row below it.
        assertEquals("Addons & Portal", (items[0] as SettingsItem.Header).title)
        assertEquals(1, edges.firstIndex)
    }

    @Test
    fun `held Up or Down stops on the edge row without wrapping`() {
        val edges = Edges(buildItems())
        assertEquals(setOf(Key.DirectionUp), edges.keysAt(edges.firstIndex))
        assertEquals(setOf(Key.DirectionDown), edges.keysAt(edges.lastIndex))
        listOf(1, 2, 10).forEach { repeats ->
            assertEquals(EdgeKeyResult.HOLD, edgeKeyResult(matchesKey = true, isKeyDown = true, repeatCount = repeats), "repeat $repeats")
        }
    }

    @Test
    fun `headers, the swatch strip and middle rows own no edge keys`() {
        val items = buildItems(swatchesOpen = true, clearSecondsLeft = 3)
        val edges = Edges(items)

        items.indices.filter { it != edges.firstIndex && it != edges.lastIndex }.forEach { index ->
            assertEquals(emptySet<Key>(), edges.keysAt(index), "${items[index].key} at $index")
        }
        assertTrue(items.any { it is SettingsItem.Swatches })
        assertTrue(items.any { it.key == CLEAR_ROW_KEY && (it as SettingsItem.Entry).title == "Undo clear library" })
    }

    @Test
    fun `edges stay on the first and last rows with the swatch strip or the undo row showing`() {
        listOf(buildItems(swatchesOpen = true), buildItems(clearSecondsLeft = 4)).forEach { items ->
            val edges = Edges(items)
            assertEquals("addons", items[edges.firstIndex].key)
            assertEquals(CLEAR_CACHE_ROW_KEY, items[edges.lastIndex].key)
        }
    }

    @Test
    fun `Auto-sync external subtitles is the last Subtitles row, right below Subtitle styling`() {
        val vm = mockk<SettingsViewModel>(relaxed = true)
        val items = buildItems(vm = vm)
        val subtitles = items.dropWhile { it.key != "h-subs" }.drop(1).takeWhile { it !is SettingsItem.Header }

        assertEquals(listOf("subs-style", "subs-autosync"), subtitles.takeLast(2).map { it.key })
        val row = subtitles.last() as SettingsItem.Entry
        assertEquals("Auto-sync external subtitles", row.title)
        assertEquals("Enabled", row.value)
        assertEquals(RowTrailing.Toggle(true), row.trailing)

        row.onClick()
        val transform = slot<(SubtitleSettings) -> SubtitleSettings>()
        verify { vm.updateSubtitles(capture(transform)) }
        assertFalse(transform.captured(SubtitleSettings()).autoSyncExternal)
        assertTrue(transform.captured(SubtitleSettings(autoSyncExternal = false)).autoSyncExternal)
    }

    @Test
    fun `entry ordinals count only focusable rows`() {
        val items = buildItems()
        val ordinals = settingsEntryOrdinals(items)
        val entries = items.count { it is SettingsItem.Entry }

        assertEquals(-1, ordinals[0])
        assertEquals((0 until entries).toList(), ordinals.filter { it >= 0 })
        items.forEachIndexed { index, item -> assertEquals(item is SettingsItem.Entry, ordinals[index] >= 0, item.key) }
    }

    /** The edge keys `MainList` gives each list index via `verticalListEdgeWrap(ordinal, lastOrdinal, …)`. */
    private class Edges(items: List<SettingsItem>) {
        private val ordinals = settingsEntryOrdinals(items)
        private val lastOrdinal = ordinals.max()
        val firstIndex = ordinals.indexOf(0)
        val lastIndex = ordinals.indexOf(lastOrdinal)

        fun keysAt(index: Int): Set<Key> = verticalEdgeKeys(ordinals[index], lastOrdinal)
    }

    private fun buildItems(
        swatchesOpen: Boolean = false,
        clearSecondsLeft: Int? = null,
        onClearCache: () -> Unit = {},
        vm: SettingsViewModel = mockk(relaxed = true),
    ) =
        settingsItems(
            state = SettingsUiState(),
            swatchesOpen = swatchesOpen,
            clearSecondsLeft = clearSecondsLeft,
            cacheBytes = 148 * MB,
            cb = SettingsCallbacks(
                open = {},
                toggleSwatches = {},
                updatePlayer = {},
                vm = vm,
                clearLibrary = {},
                undoClear = {},
                clearCache = onClearCache,
            ),
        )

    private companion object {
        const val MB = 1024L * 1024
    }
}
