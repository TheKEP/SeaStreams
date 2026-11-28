package com.seastreams.tv.ui.screens.player

import com.seastreams.tv.data.details.TitleDetailsRepository
import com.seastreams.tv.domain.model.EpisodeRef
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.SeasonInfo
import com.seastreams.tv.domain.playback.AutoNextPolicy
import com.seastreams.tv.domain.playback.NextEpisode
import com.seastreams.tv.domain.playback.PlaybackStreamResolver
import com.seastreams.tv.domain.playback.PlaybackTarget
import com.seastreams.tv.domain.playback.ShuffleMode
import com.seastreams.tv.domain.playback.ShuffleSession
import com.seastreams.tv.domain.model.PlaybackRequest
import com.seastreams.tv.domain.usecase.ResolveStreamsUseCase
import com.seastreams.tv.domain.usecase.StreamResolution
import com.seastreams.tv.testutil.testDetails
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Milestone 14: the Next episode prompt previews exactly the episode that plays. One pick per playing target,
 * so a shuffle draws once and its session advances once (sequential and shuffle-aware).
 */
class NextEpisodePreviewTest {

    private val resolveStreams = mockk<ResolveStreamsUseCase>()
    private val titles = mockk<TitleDetailsRepository>()
    private val resolver = PlaybackStreamResolver(resolveStreams, titles)
    private val switching = PlayerSwitching(resolver, mockk(relaxed = true), mockk(relaxed = true))
    private val show = testDetails(21, MediaType.TV, seasons = listOf(SeasonInfo(1, "Season 1", 3), SeasonInfo(2, "Season 2", 6)))

    init {
        coEvery { resolveStreams(any()) } returns StreamResolution(emptyList(), emptyList(), emptyList())
        coEvery { titles.episodes(any(), any()) } returns emptyList()
    }

    private fun target(episode: EpisodeRef, shuffle: ShuffleSession = ShuffleSession.Sequential, lastAired: EpisodeRef? = null) =
        PlaybackTarget(1, show.copy(lastAired = lastAired), "tt21", episode, shuffle = shuffle)

    @Test
    fun `sequential preview is the next episode, rolling into the next season`() {
        assertEquals(EpisodeRef(1, 3), switching.previewNext(target(EpisodeRef(1, 2)))?.episode)
        assertEquals(EpisodeRef(2, 1), switching.previewNext(target(EpisodeRef(1, 3)))?.episode)
    }

    @Test
    fun `caught up (last aired episode) previews nothing`() {
        assertNull(switching.previewNext(target(EpisodeRef(2, 2), lastAired = EpisodeRef(2, 2))))
        assertNull(switching.previewNext(target(EpisodeRef(2, 6))))
    }

    @Test
    fun `a shuffle preview is drawn once per target and is the one resolved`() = runTest {
        val playing = target(EpisodeRef(1, 1), ShuffleSession.start(ShuffleMode.Show, EpisodeRef(1, 1)))
        val first = switching.previewNext(playing)
        assertNotNull(first)
        repeat(10) { assertSame(first, switching.previewNext(playing)) }

        val pick = requireNotNull(first)
        val resolved = resolver.resolvePick(playing, pick, preferredAddonUrl = null).target
        assertEquals(pick.episode, resolved.episode)
        assertEquals(pick.session, resolved.shuffle)
        // The session advanced exactly once: the starting episode, then the pick.
        assertEquals(listOf(EpisodeRef(1, 1), pick.episode), resolved.shuffle.played)
    }

    @Test
    fun `season shuffle previews stay in the season`() {
        repeat(20) { seed ->
            val playing = target(EpisodeRef(2, 3), ShuffleSession.start(ShuffleMode.Season(2), EpisodeRef(2, 3)))
            val pick = requireNotNull(resolver.pickNext(playing, Random(seed)))
            assertEquals(2, pick.episode.season)
            assertFalse(pick.episode == EpisodeRef(2, 3))
        }
    }

    @Test
    fun `resolver next equals pickNext then resolvePick for the same draw`() = runTest {
        val playing = target(EpisodeRef(1, 1), ShuffleSession.start(ShuffleMode.Show, EpisodeRef(1, 1)))
        val viaNext = (resolver.next(playing, null, Random(7)) as NextEpisode.Found).playback.target
        val viaPick = resolver.resolvePick(playing, requireNotNull(resolver.pickNext(playing, Random(7))), null).target

        assertEquals(viaNext, viaPick)
    }

    @Test
    fun `the credits countdown follows Auto-play next episode and never applies to movies or trailers`() {
        val episode = PlaybackRequest.Media(1, target(EpisodeRef(1, 1)), emptyList(), 0, emptyList(), 0)
        val movie = PlaybackRequest.Media(1, PlaybackTarget(1, testDetails(5), "tt5", null), emptyList(), 0, emptyList(), 0)

        assertTrue(AutoNextPolicy.afterCreditsCountdown(episode, autoPlayNextEpisode = true))
        assertFalse(AutoNextPolicy.afterCreditsCountdown(episode, autoPlayNextEpisode = false))
        assertFalse(AutoNextPolicy.afterCreditsCountdown(movie, autoPlayNextEpisode = true))
        assertFalse(AutoNextPolicy.afterCreditsCountdown(null, autoPlayNextEpisode = true))
    }
}
