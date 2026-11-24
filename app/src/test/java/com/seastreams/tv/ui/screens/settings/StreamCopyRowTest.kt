package com.seastreams.tv.ui.screens.settings

import com.seastreams.tv.core.database.entity.ProfileEntity
import com.seastreams.tv.domain.streams.StreamProcessingSettings
import com.seastreams.tv.ui.screens.settings.streams.COPY_STREAM_SETTINGS_TITLE
import com.seastreams.tv.ui.screens.settings.streams.STREAM_COPY_ROW_KEY
import com.seastreams.tv.ui.screens.settings.streams.STREAM_FORMATTER_ROW_KEY
import com.seastreams.tv.ui.screens.settings.streams.STREAM_FORMATTING_TOGGLE_KEY
import com.seastreams.tv.ui.screens.settings.streams.copyFromSpec
import com.seastreams.tv.ui.screens.settings.streams.streamSettingsCopiedMessage
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `Settings → UI → Copy stream settings from…` (PRD §11, §13 *Backup & copy*). */
class StreamCopyRowTest {

    @Test
    fun `the row appears only with two or more profiles`() {
        assertFalse(STREAM_COPY_ROW_KEY in ui(profileCount = 1).map { it.key })
        assertFalse(STREAM_COPY_ROW_KEY in ui(profileCount = 0).map { it.key })
        assertTrue(STREAM_COPY_ROW_KEY in ui(profileCount = 2).map { it.key })
    }

    @Test
    fun `it sits right below Stream formatter, unindented, whether formatting is on or off`() {
        val on = ui(profileCount = 3).map { it.key }
        assertEquals(listOf(STREAM_FORMATTER_ROW_KEY, STREAM_COPY_ROW_KEY), on.takeLast(2))
        val off = ui(StreamProcessingSettings(formattingEnabled = false), profileCount = 3).map { it.key }
        assertEquals(listOf(STREAM_FORMATTING_TOGGLE_KEY, STREAM_COPY_ROW_KEY), off.takeLast(2))

        val row = ui(profileCount = 2).entry(STREAM_COPY_ROW_KEY)
        assertEquals(COPY_STREAM_SETTINGS_TITLE, row.title)
        assertFalse(row.indented)
        assertEquals(RowTrailing.Chevron, row.trailing)
    }

    @Test
    fun `the one-way bottom wrap edge follows the copy row`() {
        val single = ui(profileCount = 1)
        val many = ui(profileCount = 2)
        assertEquals(STREAM_FORMATTER_ROW_KEY, lastEntryKey(single))
        assertEquals(STREAM_COPY_ROW_KEY, lastEntryKey(many))
        assertEquals(settingsEntryOrdinals(single).max() + 1, settingsEntryOrdinals(many).max())
        assertEquals(settingsEntryOrdinals(many).max(), settingsEntryOrdinals(many)[many.indexOfFirst { it.key == STREAM_COPY_ROW_KEY }])
        val off = ui(StreamProcessingSettings(formattingEnabled = false), profileCount = 2)
        assertEquals(STREAM_COPY_ROW_KEY, lastEntryKey(off))
    }

    @Test
    fun `clicking opens the profile list, whose Back returns to the UI list`() {
        val opened = mutableListOf<SettingsPanel>()
        ui(profileCount = 2, open = { opened += it }).entry(STREAM_COPY_ROW_KEY).onClick()
        assertEquals(listOf<SettingsPanel>(SettingsPanel.Stream.CopyFrom), opened)
        assertEquals(SettingsPanel.Main, SettingsPanel.Stream.CopyFrom.parent())
    }

    @Test
    fun `the list offers every other profile and a pick copies from it`() {
        val profiles = listOf(profile(1, "Ken"), profile(2, "Kids"), profile(3, "Ken"))
        val copied = mutableListOf<Long>()
        val spec = copyFromSpec(profiles, activeProfileId = 1) { copied += it.id }

        assertEquals(COPY_STREAM_SETTINGS_TITLE, spec.title)
        assertEquals(listOf("Kids", "Ken"), spec.options.map { it.label })
        // Two profiles may share a name, so rows are keyed by profile id.
        assertEquals(listOf<Any?>(2L, 3L), spec.options.map { it.key })
        assertTrue(spec.options.none { it.selected })
        spec.options[1].apply()
        assertEquals(listOf(3L), copied)
        assertEquals("Stream settings copied from Kids", streamSettingsCopiedMessage("Kids"))
    }

    private fun List<SettingsItem>.entry(key: String): SettingsItem.Entry = first { it.key == key } as SettingsItem.Entry

    private fun ui(
        streams: StreamProcessingSettings = StreamProcessingSettings(),
        profileCount: Int,
        open: (SettingsPanel) -> Unit = {},
    ) = settingsItems(
        category = SettingsCategory.UI,
        state = SettingsUiState(),
        swatchesOpen = false,
        clearSecondsLeft = null,
        cacheBytes = 0,
        cb = SettingsCallbacks(open, {}, {}, mockk(relaxed = true), {}, {}, {}, {}),
        streams = streams,
        profileCount = profileCount,
    )

    private fun profile(id: Long, name: String) =
        ProfileEntity(id = id, name = name, avatarIndex = 0, isKids = false, movieCeiling = null, tvCeiling = null, allowUnrated = true, createdAtMillis = 0)
}
