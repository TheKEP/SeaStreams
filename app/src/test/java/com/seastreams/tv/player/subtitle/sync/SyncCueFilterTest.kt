package com.seastreams.tv.player.subtitle.sync

import com.seastreams.tv.player.subtitle.TimedCue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Only spoken lines are timed; display is never filtered. */
class SyncCueFilterTest {

    private fun cue(index: Int, text: String, seconds: Long = 2) = TimedCue(index * 10_000_000L, index * 10_000_000L + seconds * 1_000_000, text)

    @Test
    fun `sound captions, music-only lines and long signs are left out of the analysis`() {
        val dialogue = (0 until 25).map { cue(it, "Line $it") }
        val noise = listOf(
            cue(30, "[DOOR SLAMS]"),
            cue(31, "(SIGHS)"),
            cue(32, "♪ ♪"),
            cue(33, "♪"),
            cue(34, "- ..."),
            cue(35, "WARNING: 12 BYPASS", seconds = 12),
        )
        val kept = SyncCueFilter.dialogue(dialogue + noise)
        assertEquals(dialogue, kept)
    }

    @Test
    fun `sung lyrics, speaker-labelled and mixed lines stay`() {
        val lines = (0 until 20).map { cue(it, "Line $it") } + listOf(cue(20, "♪ Happy birthday ♪"), cue(21, "JOHN: Hello"), cue(22, "[laughs] Fine"))
        assertEquals(lines, SyncCueFilter.dialogue(lines))
    }

    @Test
    fun `a file of mostly captions or without text is kept whole`() {
        val few = (0 until 19).map { cue(it, "Line $it") } + (19 until 40).map { cue(it, "[MUSIC]") }
        assertEquals(few, SyncCueFilter.dialogue(few))
        val textless = (0 until 40).map { cue(it, "") }
        assertEquals(textless, SyncCueFilter.dialogue(textless))
    }
}
