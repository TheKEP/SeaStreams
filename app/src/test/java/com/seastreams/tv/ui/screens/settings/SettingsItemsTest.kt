package com.seastreams.tv.ui.screens.settings

import com.seastreams.tv.ui.screens.settings.streams.STREAM_FILTERING_TOGGLE_KEY
import com.seastreams.tv.ui.screens.settings.streams.STREAM_FILTERS_ROW_KEY
import com.seastreams.tv.ui.screens.settings.streams.STREAM_FORMATTER_ROW_KEY
import com.seastreams.tv.ui.screens.settings.streams.STREAM_FORMATTING_TOGGLE_KEY
import com.seastreams.tv.ui.screens.settings.streams.STREAM_SORTING_TOGGLE_KEY
import com.seastreams.tv.ui.screens.settings.streams.STREAM_SORT_ROW_KEY
import com.seastreams.tv.core.datastore.AppSettings
import com.seastreams.tv.core.datastore.PlayerSettings
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
    fun `every category lists its exact rows in order with sentence-case labels`() {
        val expected = mapOf(
            SettingsCategory.HOME to listOf("Profiles", "Manage addons", "Web portal"),
            SettingsCategory.UI to listOf(
                "Background color", "Blur unwatched episode thumbnails", "Auto-play trailers",
                "Mute auto-play trailers", "Trailer auto-play delay", "Enable stream filtering", "Stream filters",
                "Enable stream sorting", "Stream sorting", "Enable stream formatting", "Stream formatter",
            ),
            SettingsCategory.PLAYBACK to listOf(
                "Player", "Auto-play top stream", "Preferred auto-play addon", "Auto-play next episode",
                "Auto-play from continue watching", "Automatic stream failover", "Failover timeout duration",
                "Playback buffer profile", "Parallel stream connections", "Convert Dolby Vision Profile 7 to HDR",
                "Dolby Vision playback", "HDR white dimming",
            ),
            SettingsCategory.AUDIO to listOf(
                "Audio passthrough", "Dialogue boost", "Night mode", "Audio delay/advance", "Preferred audio language",
            ),
            SettingsCategory.SUBTITLES to listOf(
                "Subtitles always on/off", "Preferred subtitle language", "Hide non-preferred language",
                "Clean SDH", "Subtitle styling", "Auto-sync external subtitles",
            ),
            SettingsCategory.SOURCES to listOf(
                "Enable Usenet", "Usenet providers", "Indexers", "Search request headers", "Download request headers",
                "Update header versions", "Usenet performance", "Clear Usenet settings",
                "Enable Debrid", "Debrid providers", "Clear Debrid settings", "Web portal",
            ),
            SettingsCategory.OTHER to listOf("Clear library", "Clear cache"),
        )
        assertEquals(SettingsCategory.entries.toSet(), expected.keys)
        expected.forEach { (category, titles) -> assertEquals(titles, buildItems(category).titles(), category.label) }
    }

    @Test
    fun `only the conditional sub-settings are indented`() {
        val indented = SettingsCategory.entries.flatMap { buildItems(it).entries() }.filter { it.indented }.map { it.key }
        assertEquals(
            listOf(TRAILERS_MUTE_ROW_KEY, TRAILERS_DELAY_ROW_KEY, STREAM_FILTERS_ROW_KEY, STREAM_SORT_ROW_KEY, STREAM_FORMATTER_ROW_KEY, FALLBACK_ROW_KEY),
            indented,
        )
    }

    @Test
    fun `failover timeout sits directly beneath Automatic stream failover only while it is on`() {
        val on = buildItems(SettingsCategory.PLAYBACK).keys()
        assertEquals(FALLBACK_ROW_KEY, on[on.indexOf(FAILOVER_ROW_KEY) + 1])

        val off = buildItems(SettingsCategory.PLAYBACK, player = PlayerSettings(autoStreamFailover = false))
        assertFalse(FALLBACK_ROW_KEY in off.keys())
        assertEquals(on.size - 1, off.size)
        // The bottom edge stays HDR white dimming either way.
        assertEquals(HDR_DIM_ROW_KEY, lastEntryKey(off))
        assertEquals(HDR_DIM_ROW_KEY, lastEntryKey(buildItems(SettingsCategory.PLAYBACK)))
    }

    @Test
    fun `trailer sub-settings follow Auto-play trailers, with the stream rows always last in UI`() {
        val streamRows = listOf(
            STREAM_FILTERING_TOGGLE_KEY, STREAM_FILTERS_ROW_KEY, STREAM_SORTING_TOGGLE_KEY, STREAM_SORT_ROW_KEY,
            STREAM_FORMATTING_TOGGLE_KEY, STREAM_FORMATTER_ROW_KEY,
        )
        val on = buildItems(SettingsCategory.UI)
        assertEquals(listOf(TRAILERS_ROW_KEY, TRAILERS_MUTE_ROW_KEY, TRAILERS_DELAY_ROW_KEY) + streamRows, on.keys().takeLast(9))
        assertEquals(STREAM_FORMATTER_ROW_KEY, lastEntryKey(on))

        val off = buildItems(SettingsCategory.UI, player = PlayerSettings(autoPlayTrailers = false))
        assertFalse(TRAILERS_MUTE_ROW_KEY in off.keys())
        assertFalse(TRAILERS_DELAY_ROW_KEY in off.keys())
        assertEquals(listOf(TRAILERS_ROW_KEY) + streamRows, off.keys().takeLast(7))
    }

    @Test
    fun `the UI stream rows open their panels, whose Back returns to the UI list`() {
        val panels = mutableListOf<SettingsPanel>()
        val panelRows = listOf(STREAM_FILTERS_ROW_KEY, STREAM_SORT_ROW_KEY, STREAM_FORMATTER_ROW_KEY)
        buildItems(SettingsCategory.UI, open = { panels += it }).entries().filter { it.key in panelRows }.forEach { it.onClick() }
        assertEquals(listOf(SettingsPanel.Stream.Filters, SettingsPanel.Stream.Sort, SettingsPanel.Stream.Formatter), panels)
        panels.forEach { assertEquals(SettingsPanel.Main, it.parent()) }
        assertFalse(buildItems(SettingsCategory.SOURCES).keys().any { it.startsWith("stream-") })
    }

    @Test
    fun `the swatch strip opens under Background color without becoming an edge`() {
        val items = buildItems(SettingsCategory.UI, swatchesOpen = true)
        assertEquals(listOf(BACKGROUND_ROW_KEY, SettingsItem.Swatches.key), items.keys().take(2))
        assertEquals(BACKGROUND_ROW_KEY, firstEntryKey(items))
        assertEquals(-1, settingsEntryOrdinals(items)[1])
    }

    @Test
    fun `Profiles opens the profile switcher and Home keeps addons and portal`() {
        var opened = 0
        val panels = mutableListOf<SettingsPanel>()
        val home = buildItems(SettingsCategory.HOME, openProfiles = { opened++ }, open = { panels += it })

        assertEquals(listOf(PROFILES_ROW_KEY, ADDONS_ROW_KEY, PORTAL_ROW_KEY), home.keys())
        home.entries().forEach { it.onClick() }
        assertEquals(1, opened)
        assertEquals(listOf(SettingsPanel.Addons, SettingsPanel.Portal), panels)
    }

    @Test
    fun `Other holds Clear library then Clear cache, and the Undo row keeps its place`() {
        var cleared = 0
        val other = buildItems(SettingsCategory.OTHER, onClearCache = { cleared++ })
        val last = other.last() as SettingsItem.Entry
        assertEquals(CLEAR_CACHE_ROW_KEY, last.key)
        assertEquals("148 MB used", last.value)
        assertEquals(RowTrailing.None, last.trailing)
        last.onClick()
        assertEquals(1, cleared)

        val undo = buildItems(SettingsCategory.OTHER, clearSecondsLeft = 3)
        assertEquals(listOf(CLEAR_ROW_KEY, CLEAR_CACHE_ROW_KEY), undo.keys())
        assertEquals("Undo clear library", undo.entry(CLEAR_ROW_KEY).title)
    }

    @Test
    fun `cache size reads Calculating, 0 MB or whole megabytes`() {
        assertEquals("Calculating…", cacheSizeLabel(null))
        assertEquals("0 MB", cacheSizeLabel(0))
        assertEquals("0 MB", cacheSizeLabel(12_000))
        assertEquals("1 MB used", cacheSizeLabel(MB))
        assertEquals("148 MB used", cacheSizeLabel(148 * MB + MB / 3))
    }

    @Test
    fun `Automatic stream failover toggles and describes both states`() {
        val transforms = mutableListOf<(PlayerSettings) -> PlayerSettings>()
        val row = buildItems(SettingsCategory.PLAYBACK, updatePlayer = { transforms += it }).entry(FAILOVER_ROW_KEY)
        assertEquals(failoverDescription(true), row.value)
        assertEquals(RowTrailing.Toggle(true), row.trailing)
        row.onClick()
        assertFalse(transforms.single()(PlayerSettings()).autoStreamFailover)
        assertEquals("On: tries the next stream when one fails or doesn't start in time", failoverDescription(true))
        assertEquals("Off: a failed stream stops; pick another from Streams", failoverDescription(false))
    }

    @Test
    fun `Failover timeout duration defaults to 10s, a click cycles and the stepper stops at the ends`() {
        val transforms = mutableListOf<(PlayerSettings) -> PlayerSettings>()
        val row = buildItems(SettingsCategory.PLAYBACK, updatePlayer = { transforms += it }).entry(FALLBACK_ROW_KEY)
        assertEquals("10s", row.value)
        assertEquals(listOf(15, 20, 10, 15), listOf(10, 15, 20, 5).map(::nextFallbackTimeout))

        row.onClick()
        assertEquals(15, transforms.removeAt(0)(PlayerSettings()).fallbackTimeoutSeconds)
        val stepper = row.trailing as RowTrailing.Stepper
        fun step(down: Boolean, from: Int): Int {
            if (down) stepper.onDecrease() else stepper.onIncrease()
            return transforms.removeAt(0)(PlayerSettings(fallbackTimeoutSeconds = from)).fallbackTimeoutSeconds
        }
        assertEquals(10, step(down = true, from = 10))
        assertEquals(20, step(down = false, from = 15))
        assertEquals(20, step(down = false, from = 20))
    }

    @Test
    fun `Subtitles always on or off reads Always On and Always Off`() {
        assertEquals("Always Off", buildItems(SettingsCategory.SUBTITLES).entry("subs-mode").value)
    }

    @Test
    fun `Auto-sync external subtitles is the last Subtitles row and toggles the setting`() {
        val vm = mockk<SettingsViewModel>(relaxed = true)
        val subtitles = buildItems(SettingsCategory.SUBTITLES, vm = vm)
        assertEquals(listOf("subs-style", "subs-autosync"), subtitles.keys().takeLast(2))

        subtitles.entry("subs-autosync").onClick()
        val transform = slot<(SubtitleSettings) -> SubtitleSettings>()
        verify { vm.updateSubtitles(capture(transform)) }
        assertFalse(transform.captured(SubtitleSettings()).autoSyncExternal)
        assertTrue(transform.captured(SubtitleSettings(autoSyncExternal = false)).autoSyncExternal)
    }

    private fun List<SettingsItem>.entries() = filterIsInstance<SettingsItem.Entry>()

    private fun List<SettingsItem>.titles() = entries().map { it.title }

    private fun List<SettingsItem>.keys() = map { it.key }

    private fun List<SettingsItem>.entry(key: String): SettingsItem.Entry = first { it.key == key } as SettingsItem.Entry

    private fun buildItems(
        category: SettingsCategory,
        swatchesOpen: Boolean = false,
        clearSecondsLeft: Int? = null,
        onClearCache: () -> Unit = {},
        vm: SettingsViewModel = mockk(relaxed = true),
        player: PlayerSettings = PlayerSettings(),
        updatePlayer: ((PlayerSettings) -> PlayerSettings) -> Unit = {},
        open: (SettingsPanel) -> Unit = {},
        openProfiles: () -> Unit = {},
    ) = settingsItems(
        category = category,
        state = SettingsUiState(settings = AppSettings(player = player)),
        swatchesOpen = swatchesOpen,
        clearSecondsLeft = clearSecondsLeft,
        cacheBytes = 148 * MB,
        cb = SettingsCallbacks(
            open = open,
            toggleSwatches = {},
            updatePlayer = updatePlayer,
            vm = vm,
            clearLibrary = {},
            undoClear = {},
            clearCache = onClearCache,
            openProfiles = openProfiles,
        ),
    )

    private companion object {
        const val MB = 1024L * 1024
    }
}
