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
        assertTrue(AutoNextPolicy.afterBuiltInEnd(media(episode), autoPlayNextEpisode = true))
    }

    @Test
    fun `ExoPlayer - nothing advances with the setting off`() {
        assertFalse(AutoNextPolicy.afterBuiltInEnd(media(episode), autoPlayNextEpisode = false))
    }

    @Test
    fun `ExoPlayer - movies and trailers never advance`() {
        assertFalse(AutoNextPolicy.afterBuiltInEnd(media(movie), autoPlayNextEpisode = true))
        val trailer = PlaybackRequest.Trailer(2, "Trailer", TrailerStream(TrailerSource("https://v"), TrailerSource("https://a"), 1080))
        assertFalse(AutoNextPolicy.afterBuiltInEnd(trailer, autoPlayNextEpisode = true))
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
    fun `external - Back at 95 percent saves it as watched but never advances`() {
        val outcome = externalOutcome(mapOf("position" to (durationMs * 0.95).toInt(), "duration" to durationMs.toInt(), "end_by" to "user"))
        assertTrue(outcome.completed, "still watched")
        assertFalse(AutoNextPolicy.afterExternalReturn(episode, outcome, autoPlayNextEpisode = true))
    }

    @Test
    fun `external - Back at the very last second with end_by user never advances`() {
        val outcome = externalOutcome(mapOf("position" to durationMs.toInt(), "duration" to durationMs.toInt(), "end_by" to "user"))
        assertFalse(AutoNextPolicy.afterExternalReturn(episode, outcome, autoPlayNextEpisode = true))
    }

    @Test
    fun `external - VLC (no end_by) advances only when its position is at the very end`() {
        val atEnd = externalOutcome(mapOf("extra_position" to durationMs - 400, "extra_duration" to durationMs))
        assertTrue(AutoNextPolicy.afterExternalReturn(episode, atEnd, autoPlayNextEpisode = true))

        val backAt95 = externalOutcome(mapOf("extra_position" to (durationMs * 0.95).toLong(), "extra_duration" to durationMs))
        assertTrue(backAt95.completed, "still watched")
        assertFalse(AutoNextPolicy.afterExternalReturn(episode, backAt95, autoPlayNextEpisode = true))
    }

    @Test
    fun `external - an elapsed-time estimate marks it watched but can't tell Back from the end, so never advances`() {
        val outcome = externalOutcome(emptyMap(), returnedAt = durationMs)
        assertTrue(outcome.completed)
        assertFalse(AutoNextPolicy.afterExternalReturn(episode, outcome, autoPlayNextEpisode = true))
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
