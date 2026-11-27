package com.seastreams.tv.domain.playback

import com.seastreams.tv.domain.model.EpisodeRef
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.SeasonInfo
import com.seastreams.tv.testutil.FakeWatchProgressDao
import com.seastreams.tv.testutil.testDetails
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ExternalPlaybackProgressTest {

    private val hour = 3_600_000L
    private val launch = ExternalPlaybackLaunch(startPositionMs = 0, knownDurationMs = 2 * hour, launchedAtMillis = 1_000_000)

    private fun outcome(extras: Map<String, Any?>, returnedAfterMs: Long = 60_000, launch: ExternalPlaybackLaunch = this.launch) =
        ExternalPlaybackProgress.outcome(ExternalPlayerExtras.from(extras), launch, launch.launchedAtMillis + returnedAfterMs)

    @Nested
    inner class ExtrasParsing {
        @Test
        fun `MX Player and Just Player int extras`() {
            val extras = ExternalPlayerExtras.from(mapOf("position" to 1_234, "duration" to 5_000, "end_by" to "user"))
            assertEquals(ExternalPlayerExtras(1_234L, 5_000L, "user"), extras)
            assertFalse(extras.endedByCompletion)
        }

        @Test
        fun `VLC long extras`() {
            val extras = ExternalPlayerExtras.from(mapOf("extra_position" to 9_000_000_000L, "extra_duration" to 9_500_000_000L))
            assertEquals(9_000_000_000L, extras.positionMs)
            assertEquals(9_500_000_000L, extras.durationMs)
            assertNull(extras.endBy)
        }

        @Test
        fun `no extras at all`() {
            assertEquals(ExternalPlayerExtras(null, null, null), ExternalPlayerExtras.from(emptyMap()))
        }

        @Test
        fun `values of the wrong type are ignored`() {
            assertEquals(ExternalPlayerExtras(null, null, null), ExternalPlayerExtras.from(mapOf("position" to "12", "end_by" to 3)))
        }
    }

    @Test
    fun `early exit saves the exact reported position without marking it watched`() {
        val result = outcome(mapOf("position" to 1_800_000, "duration" to 7_200_000, "end_by" to "user"))

        assertEquals(1_800_000L, result.positionMs)
        assertEquals(7_200_000L, result.durationMs)
        assertFalse(result.completed)
        assertEquals(ProgressSource.REPORTED, result.source)
    }

    @Test
    fun `position divided by duration at 90 percent or more counts as completed`() {
        assertTrue(outcome(mapOf("position" to 6_480_000, "duration" to 7_200_000)).completed) // exactly 90%
        assertFalse(outcome(mapOf("position" to 6_479_000, "duration" to 7_200_000)).completed) // just under
    }

    @Test
    fun `only a natural end counts as endedNaturally, never a Back exit however late`() {
        assertTrue(outcome(mapOf("duration" to 7_200_000, "end_by" to "playback_completion")).endedNaturally)
        // MX / Just Player Back: watched at 99.9%, but the user left.
        val back = outcome(mapOf("position" to 7_199_500, "duration" to 7_200_000, "end_by" to "user"))
        assertTrue(back.completed)
        assertFalse(back.endedNaturally)
        // VLC sends no end_by: only a position at the very end counts.
        assertTrue(outcome(mapOf("extra_position" to 7_199_500L, "extra_duration" to 7_200_000L)).endedNaturally)
        assertFalse(outcome(mapOf("extra_position" to 7_100_000L, "extra_duration" to 7_200_000L)).endedNaturally)
        // VLC with no duration of its own: the TMDB runtime is too rough to call it the end.
        assertFalse(outcome(mapOf("extra_position" to 7_199_500L)).endedNaturally)
    }

    @Test
    fun `end_by playback_completion completes and saves the full duration`() {
        val result = outcome(mapOf("position" to 100, "duration" to 7_200_000, "end_by" to "playback_completion"))

        assertTrue(result.completed)
        assertEquals(7_200_000L, result.positionMs)
    }

    @Test
    fun `a reported position past the duration is clamped`() {
        assertEquals(5_000L, outcome(mapOf("position" to 9_000, "duration" to 5_000)).positionMs)
    }

    @Test
    fun `position without a duration uses the TMDB runtime`() {
        val result = outcome(mapOf("position" to 3_600_000))

        assertEquals(3_600_000L, result.positionMs)
        assertEquals(2 * hour, result.durationMs)
        assertFalse(result.completed)
    }

    @Test
    fun `missing extras fall back to resume point plus elapsed time`() {
        val resumed = launch.copy(startPositionMs = 600_000)
        val result = outcome(emptyMap(), returnedAfterMs = 1_200_000, launch = resumed)

        assertEquals(ProgressSource.ESTIMATED, result.source)
        assertEquals(1_800_000L, result.positionMs)
        assertEquals(2 * hour, result.durationMs)
        assertFalse(result.completed)
    }

    @Test
    fun `an elapsed-time estimate reaching the end completes the episode`() {
        val result = outcome(emptyMap(), returnedAfterMs = 3 * hour)

        assertEquals(ProgressSource.ESTIMATED, result.source)
        assertEquals(2 * hour, result.positionMs) // capped at the runtime
        assertTrue(result.completed)
    }

    @Test
    fun `missing extras and a very short visit preserve the saved progress`() {
        val result = outcome(emptyMap(), returnedAfterMs = 5_000)

        assertEquals(ProgressSource.PRESERVED, result.source)
        assertNull(result.positionMs)
        assertFalse(result.completed)
    }

    @Test
    fun `missing extras with no known runtime preserve the saved progress`() {
        val result = outcome(emptyMap(), returnedAfterMs = hour, launch = launch.copy(knownDurationMs = 0))

        assertEquals(ProgressSource.PRESERVED, result.source)
        assertNull(result.positionMs)
    }

    @Test
    fun `a zero position is treated as missing rather than wiping progress`() {
        val result = outcome(mapOf("position" to 0, "duration" to 7_200_000), returnedAfterMs = 2_000)

        assertEquals(ProgressSource.PRESERVED, result.source)
    }

    @Nested
    inner class Recording {
        private val dao = FakeWatchProgressDao()
        private val recorder = PlaybackProgressRecorder(dao, Clock.fixed(Instant.ofEpochMilli(42), ZoneOffset.UTC))
        private val show = testDetails(5, MediaType.TV, seasons = listOf(SeasonInfo(1, "Season 1", 10)), runtimeMinutes = 50)
        private val target = PlaybackTarget(1, show, "tt5", EpisodeRef(1, 3), "Episode three", 45)

        @Test
        fun `reported progress is saved for the episode and resumes next time`() = runTest {
            recorder.record(target, PlaybackOutcome(1_000_000, 2_700_000, completed = false, ProgressSource.REPORTED))

            val row = dao.rows.value.single()
            assertEquals(1, row.seasonNumber)
            assertEquals(3, row.episodeNumber)
            assertEquals("Episode three", row.episodeTitle)
            assertEquals(1_000_000L, row.positionMs)
            assertEquals(42L, row.updatedAtMillis)
            assertEquals(1_000_000L, recorder.resumePositionMs(target))
        }

        @Test
        fun `a preserved outcome never overwrites earlier progress`() = runTest {
            recorder.record(target, PlaybackOutcome(1_000_000, 2_700_000, completed = false, ProgressSource.REPORTED))

            recorder.record(target, PlaybackOutcome(null, null, completed = false, ProgressSource.PRESERVED))

            assertEquals(1_000_000L, dao.rows.value.single().positionMs)
        }

        @Test
        fun `a finished episode starts from the beginning next time`() = runTest {
            recorder.record(target, PlaybackOutcome(2_700_000, 2_700_000, completed = true, ProgressSource.REPORTED))

            assertEquals(0L, recorder.resumePositionMs(target))
        }

        @Test
        fun `episode runtime is the known duration for the elapsed-time fallback`() {
            assertEquals(45 * 60_000L, target.knownDurationMs)
            assertEquals(50 * 60_000L, target.copy(episodeRuntimeMinutes = null).knownDurationMs)
        }
    }
}
