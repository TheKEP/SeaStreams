package com.seastreams.tv.player.subtitle.sync

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The controller with frame-rate fits, other-language references, jumps and resumed results. */
@OptIn(ExperimentalCoroutinesApi::class)
class SubtitleAutoSyncRatesTest {

    private val long = conversation(seed = 31, toUs = 2_700_000_000)
    private val short = conversation(seed = 21)

    @Test
    fun `a PAL-timed file locks mid-film against the embedded track kept past the handover`() = runTest {
        val source = FakeAutoSyncSource(this, embedded = long, playFromUs = MID_FILM_US)
        val recorder = SyncRecorder()
        val external = onClock(long, SyncRate.PAL_TO_FILM, 1_500)
        val sync = recordingAutoSync(source, recorder)
        sync.start(external, referenceTrackId = "0:3")

        advanceTimeBy(120_001)
        val (at, lock) = recorder.locks.first()
        assertTrue(at in 60_000..120_000, "locked after $at ms")
        assertEquals(SyncSource.EMBEDDED, lock.source)
        assertEquals(1, lock.rate.family)
        assertTrue(mappingErrorUs(long, external, lock, MID_FILM_US, source.nowUs) <= 200_000)
        assertEquals(listOf<String?>("0:3"), source.referenceCalls, "the reference was never released")
        assertEquals(lock.rate, (recorder.statuses.last() as AutoSyncStatus.Locked).rate)

        advanceTimeBy(600_000)
        val now = recorder.locks.last().second
        assertTrue(mappingErrorUs(long, external, now, source.nowUs - 120_000_000, source.nowUs) <= 300_000, "still in sync 10 min later")
        sync.stop()
    }

    @Test
    fun `a PAL-timed file locks mid-film against audio voice activity`() = runTest {
        val source = FakeAutoSyncSource(this, embedded = null, playFromUs = MID_FILM_US)
        source.hear(long)
        val recorder = SyncRecorder()
        val external = onClock(long, SyncRate.PAL_TO_FILM, -2_000)
        recordingAutoSync(source, recorder).start(external, referenceTrackId = null)

        advanceTimeBy(150_001)
        val lock = recorder.locks.first().second
        assertEquals(SyncSource.AUDIO, lock.source)
        assertEquals(1, lock.rate.family)
        assertTrue(mappingErrorUs(long, external, lock, MID_FILM_US, source.nowUs) <= 300_000)
    }

    @Test
    fun `an other-language reference needs two agreeing passes`() = runTest {
        suspend fun lockTime(language: String?): Long {
            val source = FakeAutoSyncSource(this, embedded = short, playFromUs = START_US)
            val recorder = SyncRecorder()
            val sync = recordingAutoSync(source, recorder)
            val started = currentTime
            sync.start(shifted(short, 2_300), referenceTrackId = "0:3", referenceLanguage = language)
            while (recorder.locks.isEmpty()) advanceTimeBy(1_000)
            sync.stop()
            assertEquals(2_300L, recorder.locks.first().second.offsetMs)
            val locked = recorder.statuses.filterIsInstance<AutoSyncStatus.Locked>().first()
            assertEquals(language, locked.referenceLanguage)
            return recorder.locks.first().first - started
        }
        val same = lockTime(null)
        val other = lockTime("fra")
        assertEquals(same + AutoSyncTiming().scanIntervalMs, other)
    }

    @Test
    fun `a timing jump mid-film is followed after three agreeing passes, a drift step after two`() = runTest {
        // Right until 8:00 (+2.0 s), then 3 s later (+5.0 s): the file's release has an extra scene.
        val external = long.map { if (it.startUs < JUMP_US) shifted(listOf(it), 2_000).single() else shifted(listOf(it), 5_000).single() }
        val source = FakeAutoSyncSource(this, embedded = long, playFromUs = START_US)
        val recorder = SyncRecorder()
        recordingAutoSync(source, recorder).start(external, referenceTrackId = "0:1")

        advanceTimeBy((JUMP_US - START_US) / 1_000 + 60_000)
        assertEquals(listOf(2_000L), recorder.offsets.distinct(), "before the jump is confirmed")
        advanceTimeBy(600_000)
        assertEquals(5_000L, recorder.offsets.last())
        val jumpedAt = recorder.locks.first { it.second.offsetMs == 5_000L }.first
        // The 2-minute window is all post-jump from 10:00 (elapsed 9 min); three passes take two more minutes.
        assertTrue(jumpedAt >= (JUMP_US - START_US) / 1_000 + 120_000, "jumped at $jumpedAt ms")
        assertEquals(listOf(2_000L, 5_000L), recorder.offsets.distinct())
    }

    @Test
    fun `a resumed result applies at once and goes straight to tracking`() = runTest {
        val source = FakeAutoSyncSource(this, embedded = short, playFromUs = START_US)
        val recorder = SyncRecorder()
        val sync = recordingAutoSync(source, recorder)
        sync.start(shifted(short, 2_300), referenceTrackId = "0:3", resume = SyncLock(2_300, SyncRate.UNIT, SyncSource.EMBEDDED))
        runCurrent()
        assertEquals(listOf(0L to SyncLock(2_300, SyncRate.UNIT, SyncSource.EMBEDDED)), recorder.locks)
        assertEquals(listOf<AutoSyncStatus>(AutoSyncStatus.Locked(SyncSource.EMBEDDED, refining = false, tracking = true)), recorder.statuses)
        assertEquals(listOf<String?>("0:3"), source.referenceCalls)
        advanceTimeBy(300_000)
        assertEquals(listOf(2_300L), recorder.offsets.distinct())
        assertTrue(sync.running)

        // An audio result resumes on audio, and without a reference an embedded one does too.
        val audio = SyncRecorder()
        recordingAutoSync(FakeAutoSyncSource(this, null, START_US), audio)
            .start(short, referenceTrackId = null, resume = SyncLock(-700, SyncRate.PAL_TO_FILM, SyncSource.EMBEDDED))
        runCurrent()
        assertEquals(SyncLock(-700, SyncRate.PAL_TO_FILM, SyncSource.AUDIO), audio.locks.single().second)
        assertEquals(SyncRate.PAL_TO_FILM, (audio.statuses.single() as AutoSyncStatus.Locked).rate)
    }

    private companion object {
        const val START_US = 60_000_000L
        const val MID_FILM_US = 1_200_000_000L
        const val JUMP_US = 480_000_000L
    }
}
