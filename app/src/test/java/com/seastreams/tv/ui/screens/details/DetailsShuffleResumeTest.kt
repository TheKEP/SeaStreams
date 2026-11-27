package com.seastreams.tv.ui.screens.details

import com.seastreams.tv.domain.model.EpisodeRef
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.SeasonInfo
import com.seastreams.tv.domain.model.TitleDetails
import com.seastreams.tv.domain.playback.PlaybackTarget
import com.seastreams.tv.domain.playback.ProgressPoint
import com.seastreams.tv.domain.playback.ShuffleMode
import com.seastreams.tv.domain.playback.ShuffleSession
import com.seastreams.tv.testutil.testDetails
import com.seastreams.tv.ui.screens.playback.ShuffleResume
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** PRD §6: Details' primary button continues an active Shuffle / Shuffle Season instead of reverting to sequential. */
class DetailsShuffleResumeTest {

    private val show = testDetails(30, MediaType.TV, seasons = listOf(SeasonInfo(1, "S1", 10), SeasonInfo(2, "S2", 31), SeasonInfo(3, "S3", 26)))
    private val minute = 60_000L
    private val s2e30 = EpisodeRef(2, 30)
    private val s3e26 = EpisodeRef(3, 26)

    /** Shuffle started on S02E30, which finished naturally; auto-next picked S03E26. */
    private val showShuffle = ShuffleSession(ShuffleMode.Show, listOf(s2e30, s3e26))

    private fun target(episode: EpisodeRef, session: ShuffleSession, profileId: Long = 1, details: TitleDetails = show) =
        PlaybackTarget(profileId, details, "tt30", episode, shuffle = session)

    private fun finished(ref: EpisodeRef, at: Long) = ProgressPoint(ref, 50 * minute, 50 * minute, at)

    private fun state(resume: PlaybackTarget?, progress: List<ProgressPoint>, details: TitleDetails = show) = DetailsUiState(
        profileId = 1,
        media = details.media,
        content = DetailsContent.Loaded(details),
        progress = progress,
        progressLoaded = true,
        shuffleResume = resume,
    )

    @Test
    fun `backing out of a shuffled episode at 0 s keeps it on the button, not the sequential S2E31`() {
        // S03E26 was left under 10 s, so it has no progress row; S02E30 is the latest (finished) one.
        val details = state(target(s3e26, showShuffle), listOf(finished(s2e30, at = 100)))

        assertEquals("Play S3:E26", details.playLabel)
        assertEquals(s3e26, details.shuffleContinuation?.episode)
        assertEquals(showShuffle, details.shuffleContinuation?.shuffle, "Play keeps the session alive")
    }

    @Test
    fun `without the remembered shuffle the same progress is the sequential next-up`() {
        assertEquals("Play S2:E31", state(resume = null, listOf(finished(s2e30, at = 100))).playLabel)
    }

    @Test
    fun `a part-watched shuffled episode reads Resume`() {
        val partWatched = ProgressPoint(s3e26, 12 * minute, 50 * minute, 200)
        val details = state(target(s3e26, showShuffle), listOf(finished(s2e30, at = 100), partWatched))

        assertEquals("Resume S3:E26", details.playLabel)
        assertEquals("38m left", details.resumeNote)
    }

    @Test
    fun `a finished shuffled episode moves on to a stable shuffle pick, never a repeat`() {
        val details = state(target(s3e26, showShuffle), listOf(finished(s2e30, at = 100), finished(s3e26, at = 200)))

        val first = details.shuffleContinuation
        val again = details.copy().shuffleContinuation
        val pick = checkNotNull(first?.episode)
        assertEquals(pick, again?.episode, "the label and what Play starts agree on every recomposition")
        assertFalse(pick in showShuffle.played)
        assertNotEquals(EpisodeRef(3, 27), pick)
        assertEquals(ShuffleMode.Show, first?.shuffle?.mode)
        assertTrue(pick in checkNotNull(first?.shuffle).played, "the pick joins the session history")
        assertEquals("Play ${"S${pick.season}:E${pick.episode}"}", details.playLabel)
    }

    @Test
    fun `Shuffle Season keeps its season when continued from Details`() {
        val season = ShuffleSession(ShuffleMode.Season(1), listOf(EpisodeRef(1, 3), EpisodeRef(1, 8)))
        val details = state(target(EpisodeRef(1, 8), season), listOf(finished(EpisodeRef(1, 3), 100), finished(EpisodeRef(1, 8), 200)))

        val next = checkNotNull(details.shuffleContinuation)
        assertEquals(1, next.episode?.season)
        assertEquals(ShuffleMode.Season(1), next.shuffle.mode)

        // Backing out of the season shuffle at 0 s through Episodes: Details still shows that episode.
        val leftEarly = state(target(EpisodeRef(1, 8), season), listOf(finished(EpisodeRef(1, 3), 100)))
        assertEquals("Play S1:E8", leftEarly.playLabel)
    }

    @Test
    fun `a used-up shuffle pool falls back to the sequential next-up`() {
        val tiny = testDetails(31, MediaType.TV, seasons = listOf(SeasonInfo(1, "S1", 1), SeasonInfo(2, "S2", 3)))
        val season = ShuffleSession(ShuffleMode.Season(1), listOf(EpisodeRef(1, 1)))
        val details = state(target(EpisodeRef(1, 1), season, details = tiny), listOf(finished(EpisodeRef(1, 1), 100)), tiny)

        assertNull(details.shuffleContinuation)
        assertEquals("Play S2:E1", details.playLabel)
    }

    @Test
    fun `another show's or another profile's shuffle never changes this button`() {
        val other = testDetails(99, MediaType.TV, seasons = listOf(SeasonInfo(1, "S1", 5)))
        val progress = listOf(finished(s2e30, at = 100))

        assertEquals("Play S2:E31", state(target(EpisodeRef(1, 2), showShuffle, details = other), progress).playLabel)
        assertEquals("Play S2:E31", state(target(s3e26, showShuffle, profileId = 2), progress).playLabel)
        assertEquals("Play S2:E31", state(target(s3e26, ShuffleSession.Sequential), progress).playLabel)
    }

    // ---- ShuffleResume (PlaybackViewModel) ------------------------------------------------------------

    @Test
    fun `the last shuffled episode is remembered, and a sequential play of the same show ends it`() {
        val resume = ShuffleResume()
        resume.remember(target(s3e26, showShuffle))
        assertEquals(s3e26, resume.target.value?.episode)

        // Another title, or a movie, leaves this show's shuffle alone.
        resume.remember(PlaybackTarget(1, testDetails(5), "tt5", episode = null))
        assertEquals(s3e26, resume.target.value?.episode)

        // An Episodes overlay pick (sequential) of the same show, then exit: the shuffle is over.
        resume.remember(target(EpisodeRef(1, 4), ShuffleSession.Sequential))
        assertNull(resume.target.value)
    }

    @Test
    fun `leaving the show's Details page forgets its shuffle only`() {
        val resume = ShuffleResume()
        resume.remember(target(s3e26, showShuffle))

        resume.forget(1, testDetails(99, MediaType.TV).ref)
        resume.forget(2, show.ref)
        assertEquals(s3e26, resume.target.value?.episode)

        resume.forget(1, show.ref)
        assertNull(resume.target.value)
    }
}
