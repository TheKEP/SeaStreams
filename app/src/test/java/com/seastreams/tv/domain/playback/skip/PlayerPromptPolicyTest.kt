package com.seastreams.tv.domain.playback.skip

import com.seastreams.tv.core.datastore.PlayerSettings
import com.seastreams.tv.core.datastore.SkipPromptMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Milestone 14: which prompt shows at a position (the five prompts and their windows). */
class PlayerPromptPolicyTest {

    private val duration = 2_400_000L
    private val intro = SkipSegment(SegmentKind.INTRO, 60_000, 150_000, SegmentSource.INTRODB)
    private val recap = SkipSegment(SegmentKind.RECAP, 0, 45_000, SegmentSource.INTRODB)
    private val credits = SkipSegment(SegmentKind.CREDITS, 2_330_000, 2_395_000, SegmentSource.INTRODB)
    private val settings = PlayerSettings()

    private fun at(
        position: Long,
        segments: List<SkipSegment>,
        isEpisode: Boolean = true,
        hasNext: Boolean = true,
        s: PlayerSettings = settings,
    ) = PlayerPromptPolicy.prompt(position, duration, segments, isEpisode, hasNext, s)

    @Test
    fun `skip intro and recap show inside the segment until 2 s before its end`() {
        assertEquals(Prompt(PromptKind.SKIP_RECAP, 0, 45_000), at(10_000, listOf(recap, intro)))
        assertEquals(Prompt(PromptKind.SKIP_INTRO, 60_000, 150_000), at(60_000, listOf(recap, intro)))
        assertEquals(Prompt(PromptKind.SKIP_INTRO, 60_000, 150_000), at(147_999, listOf(intro)))
        assertNull(at(148_000, listOf(intro)))
        assertNull(at(59_999, listOf(intro)))
    }

    @Test
    fun `modes off, button and auto`() {
        assertNull(at(70_000, listOf(intro), s = settings.copy(skipIntroRecap = SkipPromptMode.OFF)))
        assertEquals(true, at(70_000, listOf(intro), s = settings.copy(skipIntroRecap = SkipPromptMode.AUTO))?.auto)
        assertEquals(false, at(70_000, listOf(intro))?.auto)
    }

    @Test
    fun `episode credits with a short tail offer Next episode with a countdown`() {
        assertEquals(Prompt(PromptKind.NEXT_EPISODE, 2_330_000, null, countdown = true), at(2_340_000, listOf(credits)))
        // Auto-play next episode off: a plain button.
        assertEquals(false, at(2_340_000, listOf(credits), s = settings.copy(autoPlayNextEpisode = false))?.countdown)
        // Caught up, or the prompt turned off: nothing.
        assertNull(at(2_340_000, listOf(credits), hasNext = false))
        assertNull(at(2_340_000, listOf(credits), s = settings.copy(nextEpisodePrompt = false)))
    }

    @Test
    fun `episode credits followed by 20 s or more offer Skip credits to the credits end`() {
        val early = credits.copy(startMs = 2_200_000, endMs = 2_300_000)

        assertEquals(Prompt(PromptKind.SKIP_CREDITS, 2_200_000, 2_300_000), at(2_210_000, listOf(early)))
        // After the credits, Next episode only in the last 30 s, without a countdown.
        assertNull(at(2_320_000, listOf(early)))
        assertEquals(Prompt(PromptKind.NEXT_EPISODE, 2_370_000, null), at(2_380_000, listOf(early)))
    }

    @Test
    fun `movie credits skip to the end, never Next episode`() {
        assertEquals(Prompt(PromptKind.SKIP_CREDITS, 2_330_000, duration), at(2_340_000, listOf(credits), isEpisode = false))
        assertNull(at(2_396_000, listOf(credits), isEpisode = false))
    }

    @Test
    fun `a known post-credits scene is the credits skip target, then Next episode follows it`() {
        val credits = credits.copy(startMs = 2_200_000, endMs = 2_300_000)
        val scene = SkipSegment(SegmentKind.POST_CREDITS, 2_300_000, 2_360_000, SegmentSource.CHAPTERS)

        assertEquals(Prompt(PromptKind.SKIP_TO_POST_CREDITS, 2_200_000, 2_300_000), at(2_250_000, listOf(credits, scene)))
        assertNull(at(2_320_000, listOf(credits, scene)))
        assertEquals(Prompt(PromptKind.NEXT_EPISODE, 2_360_000, null, countdown = true), at(2_365_000, listOf(credits, scene)))
    }

    @Test
    fun `credits auto-skip with auto-play next goes straight to the next episode`() {
        val auto = settings.copy(skipCredits = SkipPromptMode.AUTO)

        assertEquals(Prompt(PromptKind.NEXT_EPISODE, 2_330_000, null, auto = true), at(2_340_000, listOf(credits), s = auto))
    }

    @Test
    fun `without data Next episode shows in the last 30 s only`() {
        assertNull(at(2_369_999, emptyList()))
        assertEquals(Prompt(PromptKind.NEXT_EPISODE, 2_370_000, null), at(2_370_000, emptyList()))
        assertNull(at(2_380_000, emptyList(), isEpisode = false))
        assertNull(PlayerPromptPolicy.prompt(10_000, 0, listOf(intro), true, true, settings))
    }
}
