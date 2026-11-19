package com.seastreams.tv.ui.screens.player

import androidx.compose.ui.input.key.Key
import com.seastreams.tv.domain.playback.SubtitleOption
import com.seastreams.tv.domain.playback.SubtitleSource
import com.seastreams.tv.ui.theme.EdgeKeyResult
import com.seastreams.tv.ui.theme.edgeKeyResult
import com.seastreams.tv.ui.theme.verticalEdgeKeys
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The in-player Subtitles overlay: §1 two-way wrap, opening on the active track, per-title memory. */
class SubtitlePanelTest {

    private val embedded = SubtitleOption("0:2", "English", "eng", SubtitleSource.EMBEDDED)
    private val forced = SubtitleOption("0:3", "English · Forced", "eng", SubtitleSource.EMBEDDED)
    private val external = SubtitleOption("https://subs/en.srt", "English · OpenSubtitles", "eng", SubtitleSource.EXTERNAL)
    private val state = PlayerUiState(subtitleOptions = listOf(embedded, forced, external))

    @Test
    fun `rows run Off, tracks, Options, Style, ending on Background`() {
        val layout = subtitlePanelLayout(state)
        assertEquals(
            listOf("off", "sub-0:2", "sub-0:3", "sub-https://subs/en.srt", "h-sdh", "sdh", "h-style") + STYLE_KEYS,
            layout.keys,
        )
        assertEquals(0, layout.firstIndex)
        assertEquals(layout.indexOf("background"), layout.lastIndex)
    }

    @Test
    fun `a fresh Up on Off wraps to Background and a fresh Down on Background wraps to Off`() {
        val layout = subtitlePanelLayout(state)
        assertEquals(setOf(Key.DirectionUp), verticalEdgeKeys(layout.firstIndex, layout.lastIndex))
        assertEquals(setOf(Key.DirectionDown), verticalEdgeKeys(layout.lastIndex, layout.lastIndex))
        assertEquals(EdgeKeyResult.WRAP, edgeKeyResult(matchesKey = true, isKeyDown = true, repeatCount = 0))
        // Headers and middle rows own no edge keys: D-pad moves normally between them.
        (1 until layout.lastIndex).forEach { assertEquals(emptySet<Key>(), verticalEdgeKeys(it, layout.lastIndex), layout.keys[it]) }
    }

    @Test
    fun `held Up or Down stops on the edge row, and a single row consumes both in place`() {
        listOf(1, 2, 10).forEach { assertEquals(EdgeKeyResult.HOLD, edgeKeyResult(matchesKey = true, isKeyDown = true, repeatCount = it)) }
        assertEquals(setOf(Key.DirectionUp, Key.DirectionDown), verticalEdgeKeys(0, 0))
    }

    @Test
    fun `the menu opens on the active track, or on Off`() {
        assertEquals(0, subtitlePanelLayout(state).activeIndex, "subtitles off")
        assertEquals(1, subtitlePanelLayout(state.copy(selectedSubtitleId = embedded.id)).activeIndex)
        val onExternal = subtitlePanelLayout(state.copy(selectedSubtitleId = external.id))
        assertEquals(onExternal.indexOf("sub-${external.id}"), onExternal.activeIndex)
        assertEquals(0, subtitlePanelLayout(state.copy(selectedSubtitleId = "gone")).activeIndex, "a vanished track falls back to Off")
    }

    @Test
    fun `the title's choice is matched on a new stream by source and language, same label first`() {
        val newStream = listOf(
            SubtitleOption("1:1", "English · Forced", "en", SubtitleSource.EMBEDDED),
            SubtitleOption("1:2", "English", "en", SubtitleSource.EMBEDDED),
            external,
        )
        assertEquals("1:2", SubtitleChoice.of(embedded).match(newStream)?.id)
        assertEquals("1:1", SubtitleChoice.of(forced).match(newStream)?.id)
        assertEquals(external.id, SubtitleChoice.of(external).match(newStream)?.id)
        // Same language, different label: still that language's first embedded track.
        assertEquals("1:1", SubtitleChoice(SubtitleSource.EMBEDDED, "eng", "English SDH").match(newStream)?.id)
        assertNull(SubtitleChoice(SubtitleSource.EMBEDDED, "fre", "French").match(newStream), "no match: the default applies")
    }

    @Test
    fun `an explicit Off is remembered as Off`() {
        val off = SubtitleChoice.of(null)
        assertEquals(SubtitleChoice.OFF, off)
        assertEquals(true, off.isOff)
        assertNull(off.match(listOf(embedded)))
    }
}
