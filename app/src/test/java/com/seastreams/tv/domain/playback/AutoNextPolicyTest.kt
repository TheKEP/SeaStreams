package com.seastreams.tv.domain.playback

import com.seastreams.tv.domain.model.EpisodeRef
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.PlaybackRequest
import com.seastreams.tv.domain.model.SeasonInfo
import com.seastreams.tv.domain.model.TrailerSource
import com.seastreams.tv.domain.model.TrailerStream
import com.seastreams.tv.testutil.testDetails
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AutoNextPolicyTest {

    private val show = testDetails(5, MediaType.TV, seasons = listOf(SeasonInfo(1, "Season 1", 10)), runtimeMinutes = 40)
    private val episode = PlaybackTarget(1, show, "tt5", EpisodeRef(1, 3))
    private val movie = PlaybackTarget(1, testDetails(9), "tt9", episode = null)
    private val durationMs = 40 * 60_000L

    private fun media(target: PlaybackTarget) = PlaybackRequest.Media(1, target, emptyList(), 0, emptyList(), 0)

    // ---- Built-in ExoPlayer ----------------------------------------------------------------------

    @Test
    fun `ExoPlayer - an episode that plays to the end advances`() {
        assertTrue(AutoNextPolicy.afterBuiltInStop(media(episode), PlayerStop.ENDED, durationMs, durationMs, autoPlayNextEpisode = true))
    }

    @Test
    fun `ExoPlayer - leaving at 90 percent or later counts as finished`() {
        val atNinety = (durationMs * 0.9).toLong()
        assertTrue(AutoNextPolicy.afterBuiltInStop(media(episode), PlayerStop.USER_EXIT, atNinety, durationMs, autoPlayNextEpisode = true))
    }

    @Test
    fun `ExoPlayer - an early exit saves progress without advancing`() {
        val early = (durationMs * 0.89).toLong()
        assertFalse(AutoNextPolicy.afterBuiltInStop(media(episode), PlayerStop.USER_EXIT, early, durationMs, autoPlayNextEpisode = true))
    }

    @Test
    fun `ExoPlayer - nothing advances with the setting off`() {
        assertFalse(AutoNextPolicy.afterBuiltInStop(media(episode), PlayerStop.ENDED, durationMs, durationMs, autoPlayNextEpisode = false))
    }

    @Test
    fun `ExoPlayer - movies and trailers never advance`() {
        assertFalse(AutoNextPolicy.afterBuiltInStop(media(movie), PlayerStop.ENDED, durationMs, durationMs, autoPlayNextEpisode = true))
        val trailer = PlaybackRequest.Trailer(2, "Trailer", TrailerStream(TrailerSource("https://v"), TrailerSource("https://a"), 1080))
        assertFalse(AutoNextPolicy.afterBuiltInStop(trailer, PlayerStop.ENDED, durationMs, durationMs, autoPlayNextEpisode = true))
    }

    // ---- External players ------------------------------------------------------------------------

    private val launch = ExternalPlaybackLaunch(startPositionMs = 0, knownDurationMs = durationMs, launchedAtMillis = 0)

    private fun externalOutcome(extras: Map<String, Any?>, returnedAt: Long = 60_000L) =
        ExternalPlaybackProgress.outcome(ExternalPlayerExtras.from(extras), launch, returnedAt)

    @Test
    fun `external - end_by playback_completion advances`() {
        val outcome = externalOutcome(mapOf("end_by" to "playback_completion", "duration" to durationMs.toInt()))
        assertTrue(AutoNextPolicy.afterExternalReturn(episode, outcome, autoPlayNextEpisode = true))
    }

    @Test
    fun `external - a reported position at 90 percent advances`() {
        val outcome = externalOutcome(mapOf("position" to (durationMs * 0.95).toInt(), "duration" to durationMs.toInt(), "end_by" to "user"))
        assertTrue(AutoNextPolicy.afterExternalReturn(episode, outcome, autoPlayNextEpisode = true))
    }

    @Test
    fun `external - elapsed runtime completion advances when the player sends no position`() {
        val outcome = externalOutcome(emptyMap(), returnedAt = durationMs)
        assertTrue(outcome.completed)
        assertTrue(AutoNextPolicy.afterExternalReturn(episode, outcome, autoPlayNextEpisode = true))
    }

    @Test
    fun `external - an early exit does not advance`() {
        val outcome = externalOutcome(mapOf("position" to 600_000, "duration" to durationMs.toInt(), "end_by" to "user"))
        assertFalse(AutoNextPolicy.afterExternalReturn(episode, outcome, autoPlayNextEpisode = true))
    }

    @Test
    fun `external - setting off or a movie never advances`() {
        val finished = externalOutcome(mapOf("end_by" to "playback_completion", "duration" to durationMs.toInt()))
        assertFalse(AutoNextPolicy.afterExternalReturn(episode, finished, autoPlayNextEpisode = false))
        assertFalse(AutoNextPolicy.afterExternalReturn(movie, finished, autoPlayNextEpisode = true))
    }
}
