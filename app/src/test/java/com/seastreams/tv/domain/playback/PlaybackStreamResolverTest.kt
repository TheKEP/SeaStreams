package com.seastreams.tv.domain.playback

import com.seastreams.tv.data.details.TitleDetailsRepository
import com.seastreams.tv.domain.model.EpisodeRef
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.PlaybackRequest
import com.seastreams.tv.domain.model.SeasonInfo
import com.seastreams.tv.domain.model.StreamAddon
import com.seastreams.tv.domain.model.StreamSource
import com.seastreams.tv.domain.usecase.ResolveStreamsUseCase
import com.seastreams.tv.domain.usecase.StreamResolution
import com.seastreams.tv.testutil.testDetails
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** The one next-episode lookup shared by the HUD button and built-in / external Auto-Play Next Episode. */
class PlaybackStreamResolverTest {

    private val resolveStreams = mockk<ResolveStreamsUseCase>()
    private val titles = mockk<TitleDetailsRepository>()
    private val resolver = PlaybackStreamResolver(resolveStreams, titles)
    private val show = testDetails(21, MediaType.TV, seasons = listOf(SeasonInfo(1, "Season 1", 6), SeasonInfo(2, "Season 2", 6)))

    init {
        coEvery { resolveStreams(any()) } returns StreamResolution(emptyList(), emptyList(), emptyList())
        coEvery { titles.episodes(any(), any()) } returns emptyList()
    }

    private fun target(episode: EpisodeRef, shuffle: ShuffleSession) = PlaybackTarget(1, show, "tt21", episode, shuffle = shuffle)

    private suspend fun nextTarget(finished: PlaybackTarget, random: Random): PlaybackTarget {
        val next = resolver.next(finished, preferredAddonUrl = null, random = random)
        assertTrue(next is NextEpisode.Found, "expected a next episode, got $next")
        return (next as NextEpisode.Found).playback.target
    }

    @Test
    fun `season shuffle carries the session forward and never leaves the season`() = runTest {
        val random = Random(4)
        var current = target(EpisodeRef(2, 3), ShuffleSession.start(ShuffleMode.Season(2), EpisodeRef(2, 3)))
        repeat(20) {
            val next = nextTarget(current, random)
            assertEquals(2, next.episode?.season)
            assertNotEquals(current.episode, next.episode)
            assertEquals(ShuffleMode.Season(2), next.shuffle.mode)
            assertTrue(next.episode in next.shuffle.played)
            current = next
        }
    }

    @Test
    fun `show shuffle carries the session forward`() = runTest {
        val first = target(EpisodeRef(1, 1), ShuffleSession.start(ShuffleMode.Show, EpisodeRef(1, 1)))
        val next = nextTarget(first, Random(8))

        assertEquals(ShuffleMode.Show, next.shuffle.mode)
        assertEquals(listOf(EpisodeRef(1, 1), next.episode), next.shuffle.played)
    }

    /**
     * One built-in natural completion, hop for hop: `STATE_ENDED` → AutoNextPolicy → resolver.next →
     * `PlaybackRequest.Media(target = playback.target)` exactly as `PlayerSwitching.start` builds it.
     */
    private suspend fun naturalAutoNext(playing: PlaybackRequest.Media, random: Random): PlaybackRequest.Media {
        assertTrue(AutoNextPolicy.afterBuiltInEnd(playing, autoPlayNextEpisode = true))
        val found = resolver.next(playing.target, preferredAddonUrl = null, random = random)
        assertTrue(found is NextEpisode.Found, "expected a next episode, got $found")
        val playback = (found as NextEpisode.Found).playback
        return PlaybackRequest.Media(playing.sessionId, playback.target, playback.resolution.streams, 0, playback.resolution.subtitles, 0)
    }

    @Test
    fun `show shuffle survives consecutive natural completions in the built-in player`() = runTest {
        val random = Random(21)
        val first = EpisodeRef(1, 4)
        var playing = PlaybackRequest.Media(1, target(first, ShuffleSession.start(ShuffleMode.Show, first)), emptyList(), 0, emptyList(), 0)
        val played = mutableListOf(first)
        repeat(6) {
            playing = naturalAutoNext(playing, random)
            assertEquals(ShuffleMode.Show, playing.shuffle, "still a show shuffle after natural completion ${it + 1}")
            played += checkNotNull(playing.target.episode)
        }
        assertEquals(played.size, played.toSet().size, "no repeats before the pool is used up")
        assertTrue(played.zipWithNext().any { (a, b) -> b != EpisodeRef(a.season, a.episode + 1) }, "not sequential: $played")
    }

    @Test
    fun `season shuffle survives consecutive natural completions in the built-in player`() = runTest {
        val random = Random(33)
        val first = EpisodeRef(2, 2)
        var playing = PlaybackRequest.Media(1, target(first, ShuffleSession.start(ShuffleMode.Season(2), first)), emptyList(), 0, emptyList(), 0)
        val played = mutableListOf(first)
        repeat(5) {
            playing = naturalAutoNext(playing, random)
            assertEquals(ShuffleMode.Season(2), playing.shuffle)
            played += checkNotNull(playing.target.episode)
        }
        assertTrue(played.all { it.season == 2 })
        assertEquals((1..6).map { EpisodeRef(2, it) }.toSet(), played.toSet(), "the whole season, once each")
    }

    @Test
    fun `shuffle survives natural completions in an external player`() = runTest {
        val random = Random(5)
        val first = EpisodeRef(1, 2)
        var current = target(first, ShuffleSession.start(ShuffleMode.Show, first))
        val launch = ExternalPlaybackLaunch(startPositionMs = 0, knownDurationMs = 0, launchedAtMillis = 0)
        repeat(6) {
            val outcome = ExternalPlaybackProgress.outcome(
                ExternalPlayerExtras.from(mapOf("end_by" to "playback_completion", "duration" to 2_700_000)),
                launch,
                returnedAtMillis = 2_700_000,
            )
            assertTrue(AutoNextPolicy.afterExternalReturn(current, outcome, autoPlayNextEpisode = true))
            current = nextTarget(current, random)
            assertEquals(ShuffleMode.Show, current.shuffle.mode)
        }
    }

    @Test
    fun `without a shuffle the next episode is sequential`() = runTest {
        val next = nextTarget(target(EpisodeRef(1, 6), ShuffleSession.Sequential), Random(0))

        assertEquals(EpisodeRef(2, 1), next.episode)
        assertEquals(ShuffleSession.Sequential, next.shuffle)
    }

    @Test
    fun `auto-play still picks the preferred addon's first stream from the flat list`() = runTest {
        val streams = listOf(stream("https://a", 1), stream("https://b", 1), stream("https://b", 2))
        val addons = listOf(StreamAddon("https://a", "A"), StreamAddon("https://b", "B"), StreamAddon("https://empty", "Empty"))
        coEvery { resolveStreams(any()) } returns StreamResolution(streams, emptyList(), emptyList(), addons)
        val movie = PlaybackTarget(1, testDetails(5), "tt5", episode = null)

        val preferred = resolver.resolve(movie, preferredAddonUrl = "https://b")
        val unpreferred = resolver.resolve(movie, preferredAddonUrl = null)

        assertEquals(1, preferred.topIndex)
        assertEquals(streams, preferred.resolution.streams)
        assertEquals(addons, preferred.resolution.streamAddons)
        assertEquals(0, unpreferred.topIndex)
    }

    private fun stream(addonUrl: String, n: Int) = StreamSource(addonUrl, addonUrl, "$addonUrl/$n", "S$n", "", emptyMap())

    @Test
    fun `the last episode of a sequential session is caught up`() = runTest {
        assertEquals(NextEpisode.CaughtUp, resolver.next(target(EpisodeRef(2, 6), ShuffleSession.Sequential), null))
    }
}
