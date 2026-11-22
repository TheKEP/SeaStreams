package com.seastreams.tv.player.subtitle.sync

import com.seastreams.tv.domain.playback.SubtitleOption
import com.seastreams.tv.domain.playback.SubtitleSource
import com.seastreams.tv.player.subtitle.TimedCue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/** The auto-sync controller in virtual time, with reference data that grows as playback goes on. */
@OptIn(ExperimentalCoroutinesApi::class)
class SubtitleAutoSyncTest {

    private val truth = conversation(seed = 21)

    /** Playback starts at 60 s; the fake player has heard everything up to 60 s + the elapsed time. */
    private inner class FakeSource(private val scope: TestScope, private val embedded: List<TimedCue>?) : AutoSyncSource {
        val referenceCalls = mutableListOf<String?>()
        val collectingCalls = mutableListOf<Boolean>()
        override val speechLog = SpeechEnergyLog()

        override fun setReferenceTrack(id: String?) {
            referenceCalls += id
        }

        override fun referenceSnapshot(): ReferenceSnapshot {
            val cues = embedded ?: return ReferenceSnapshot(emptyList(), emptyList())
            val nowUs = PLAY_FROM_US + scope.currentTime * 1_000
            return ReferenceSnapshot(cues.filter { it.startUs >= PLAY_FROM_US && it.endUs <= nowUs }, listOf(ObservedSpan(PLAY_FROM_US, nowUs)))
        }

        override fun setSpeechCollecting(on: Boolean) {
            collectingCalls += on
        }
    }

    /** The audio tap logging one 100 ms cell per 100 ms of playback: voiced (-30 dB) during [speech]. */
    private fun TestScope.hear(log: SpeechEnergyLog, speech: List<TimedCue>) {
        val grid = ActivityGrid.fromCues(speech, 0, 40_000)
        backgroundScope.launch {
            var cell = cellOf(PLAY_FROM_US)
            while (true) {
                delay(100)
                if (grid.active[cell.toInt()]) log.record(cell, -30f, 0.8f) else log.record(cell, -60f, 0.2f)
                cell++
            }
        }
    }

    private class Recorder {
        val detected = mutableListOf<Pair<Long, Long>>() // (time ms, offset)
        val locks = mutableListOf<SyncLock>()
        val statuses = mutableListOf<AutoSyncStatus>()
    }

    private fun TestScope.autoSync(source: AutoSyncSource, recorder: Recorder) = SubtitleAutoSync(
        backgroundScope,
        source,
        onDetected = { recorder.detected += currentTime to it.offsetMs; recorder.locks += it },
        onStatus = { recorder.statuses += it },
        analysis = StandardTestDispatcher(testScheduler),
    )

    @Test
    fun `an embedded reference locks within about 20 s, then a full-tier pass confirms it`() = runTest {
        val source = FakeSource(this, embedded = truth)
        val recorder = Recorder()
        val sync = autoSync(source, recorder)
        sync.start(shifted(truth, 2_300), referenceTrackId = "0:3")
        runCurrent()
        assertEquals(listOf<String?>("0:3"), source.referenceCalls)
        assertEquals(AutoSyncStatus.Scanning(SyncSource.EMBEDDED, 0), recorder.statuses.first())

        advanceTimeBy(30_001)
        val (at, offset) = recorder.detected.first()
        assertEquals(2_300L, offset)
        assertTrue(at <= 30_000, "locked after $at ms")
        assertEquals(AutoSyncStatus.Locked(SyncSource.EMBEDDED, refining = true), recorder.statuses.last())

        advanceTimeBy(AutoSyncTiming().refineTimeoutMs)
        assertEquals(AutoSyncStatus.Locked(SyncSource.EMBEDDED, refining = false, tracking = true), recorder.statuses.last())
        assertEquals(listOf(2_300L), recorder.detected.map { it.second }, "confirmed, not re-applied")
        assertEquals("0:3", source.referenceCalls.last(), "reference stays on while drift is tracked")
        assertTrue(sync.running)
        sync.stop()
        assertNull(source.referenceCalls.last(), "stop turns it off")
        assertEquals(listOf(true, false), source.collectingCalls, "audio tap runs from the start, off at stop")
    }

    @Test
    fun `a ~5 s offset locks against an embedded track timed by another author`() = runTest {
        // PGS/SRT from the disc vs an addon .srt: different durations and line merges, same starts.
        val source = FakeSource(this, embedded = truth)
        val recorder = Recorder()
        autoSync(source, recorder).start(shifted(retimed(truth, seed = 9), 5_000), referenceTrackId = "0:3")
        advanceTimeBy(30_001)
        assertEquals(5_000L, recorder.detected.firstOrNull()?.second, "statuses ${recorder.statuses.takeLast(2)}")
        assertEquals(SyncSource.EMBEDDED, (recorder.statuses.last() as AutoSyncStatus.Locked).source)
    }

    @Test
    fun `scanning reports time heard, cues received and the best candidate`() = runTest {
        val source = FakeSource(this, embedded = truth)
        val recorder = Recorder()
        autoSync(source, recorder).start(shifted(truth, 2_300), referenceTrackId = "0:3")
        advanceTimeBy(10_001)
        val first = recorder.statuses.last() as AutoSyncStatus.Scanning
        assertEquals(10, first.heardSeconds)
        assertTrue(first.cues > 0, "cues counted before there's enough to correlate")
        assertNull(first.bestOffsetMs, "under 15 s: no candidate yet")
        advanceTimeBy(5_000)
        val scanning = recorder.statuses.filterIsInstance<AutoSyncStatus.Scanning>().last()
        assertEquals(15, scanning.heardSeconds)
        assertEquals(2_300L, scanning.bestOffsetMs)
        assertTrue(requireNotNull(scanning.bestPeak) > 0.9)
        assertEquals(100, scanning.onsetPercent)
    }

    @Test
    fun `a signs-only or silent embedded track is dropped after 20 s and audio takes over at once`() = runTest {
        // Two PGS cues in the first 20 s: a signs/songs track. Speech has been logged since 0 s.
        val signs = listOf(TimedCue(62_000_000, 64_000_000, ""), TimedCue(70_000_000, 71_000_000, ""))
        val source = FakeSource(this, embedded = signs)
        hear(source.speechLog, truth)
        val recorder = Recorder()
        autoSync(source, recorder).start(shifted(truth, 5_000), referenceTrackId = "0:2")

        advanceTimeBy(20_001)
        assertEquals(listOf<String?>("0:2", null), source.referenceCalls, "embedded given up at 20 s")
        val audio = recorder.statuses.last() as AutoSyncStatus.Scanning
        assertEquals(SyncSource.AUDIO, audio.source)
        assertEquals(2, audio.embeddedCues)
        advanceTimeBy(10_000)
        val (at, offset) = recorder.detected.first()
        assertTrue(at <= 30_000, "audio locked after $at ms")
        assertTrue(abs(offset - 5_000) <= 200, "locked at $offset")
    }

    @Test
    fun `an embedded track that never converges hands over to audio at 30 s`() = runTest {
        // Plenty of cues, but from another film: the embedded gate never passes.
        val source = FakeSource(this, embedded = conversation(seed = 55))
        hear(source.speechLog, truth)
        val recorder = Recorder()
        autoSync(source, recorder).start(shifted(truth, 5_000), referenceTrackId = "0:2")

        advanceTimeBy(29_999)
        assertEquals(SyncSource.EMBEDDED, (recorder.statuses.last() as AutoSyncStatus.Scanning).source)
        advanceTimeBy(2)
        assertEquals(SyncSource.AUDIO, (recorder.statuses.last() as AutoSyncStatus.Scanning).source, "handed over at 30 s")
        // The reference stays on for rate search until audio locks, then is released at once.
        assertEquals(listOf<String?>("0:2"), source.referenceCalls)
        advanceTimeBy(10_000)
        assertTrue(abs(recorder.detected.first().second - 5_000) <= 200)
        assertEquals(SyncSource.AUDIO, (recorder.statuses.last() as AutoSyncStatus.Locked).source)
        assertEquals(listOf<String?>("0:2", null), source.referenceCalls)
    }

    @Test
    fun `voice activity locks after two agreeing passes and is refined to the exact offset`() = runTest {
        val source = FakeSource(this, embedded = null)
        hear(source.speechLog, truth)
        val recorder = Recorder()
        autoSync(source, recorder).start(shifted(truth, -4_700), referenceTrackId = null)

        advanceTimeBy(45_001)
        assertEquals(listOf(true), source.collectingCalls)
        val (at, offset) = recorder.detected.first()
        assertTrue(at <= 45_000, "locked after $at ms")
        assertTrue(abs(offset + 4_700) <= 200, "locked at $offset")

        advanceTimeBy(AutoSyncTiming().refineTimeoutMs)
        // Audio timing resolves to ±0.1 s (the speech hangover biases it by half a cell).
        // Onset refinement removes the half-cell hangover bias: exact.
        assertEquals(-4_700L, recorder.detected.last().second)
        assertEquals(AutoSyncStatus.Locked(SyncSource.AUDIO, refining = false, tracking = true), recorder.statuses.last())
        assertEquals(true, source.collectingCalls.last(), "the tap keeps listening while drift is tracked")
    }

    @Test
    fun `an embedded track that never emits a cue falls back to voice activity`() = runTest {
        val source = FakeSource(this, embedded = emptyList())
        hear(source.speechLog, truth)
        val recorder = Recorder()
        autoSync(source, recorder).start(shifted(truth, 1_200), referenceTrackId = "0:2")

        advanceTimeBy(AutoSyncTiming().embeddedMinCueWindowMs + 1)
        assertTrue(recorder.detected.isEmpty())
        assertEquals(listOf<String?>("0:2", null), source.referenceCalls)
        val audio = recorder.statuses.last() as AutoSyncStatus.Scanning
        assertEquals(SyncSource.AUDIO, audio.source)
        assertEquals(0, audio.embeddedCues)
        advanceTimeBy(AutoSyncTiming().refineTimeoutMs)
        assertEquals(1_200L, recorder.detected.last().second)
    }

    @Test
    fun `an unrelated soundtrack, even with a long shared silence, never applies anything`() = runTest {
        // Both this speech and the subtitle file end at 10:00, so the last minutes are silent in both.
        val source = FakeSource(this, embedded = null)
        hear(source.speechLog, conversation(seed = 77))
        val recorder = Recorder()
        val sync = autoSync(source, recorder)
        sync.start(shifted(truth, 3_000), referenceTrackId = null)

        advanceTimeBy(AutoSyncTiming().voiceTimeoutMs + 1)
        assertTrue(recorder.detected.isEmpty(), "detected ${recorder.detected} statuses ${recorder.statuses.filter { it !is AutoSyncStatus.Scanning }}")
        assertEquals(AutoSyncStatus.NoMatch, recorder.statuses.last())
        assertFalse(sync.running)
        assertEquals(false, source.collectingCalls.last())
    }

    @Test
    fun `stop (a manual nudge, track change or session end) cancels without applying`() = runTest {
        val source = FakeSource(this, embedded = truth)
        val recorder = Recorder()
        val sync = autoSync(source, recorder)
        sync.start(shifted(truth, 2_000), referenceTrackId = "0:1")
        advanceTimeBy(5_000)

        sync.stop()
        assertNull(source.referenceCalls.last(), "turned off immediately, not on the next dispatch")
        advanceTimeBy(120_000)
        assertTrue(recorder.detected.isEmpty())
    }

    @Test
    fun `offsets under 0_1 s apply as 0_0 s`() = runTest {
        val source = FakeSource(this, embedded = truth)
        val recorder = Recorder()
        autoSync(source, recorder).start(truth, referenceTrackId = "0:1")
        advanceTimeBy(60_001)
        assertEquals(0L, recorder.detected.first().second)
    }

    @Test
    fun `the reference prefers a full same-language embedded track, never a partial one`() {
        val options = listOf(
            SubtitleOption("0:1", "English · Forced", "eng", SubtitleSource.EMBEDDED),
            SubtitleOption("0:4", "English (Signs & Songs)", "eng", SubtitleSource.EMBEDDED),
            SubtitleOption("0:5", "Foreign Parts Only", "eng", SubtitleSource.EMBEDDED),
            SubtitleOption("0:6", "Director's Commentary", "eng", SubtitleSource.EMBEDDED),
            SubtitleOption("0:2", "English SDH", "en", SubtitleSource.EMBEDDED),
            SubtitleOption("0:3", "German", "ger", SubtitleSource.EMBEDDED),
            SubtitleOption("https://x", "English · OpenSubtitles", "eng", SubtitleSource.EXTERNAL),
        )
        assertEquals("0:2", ReferenceTrackPicker.pick("eng", options)?.option?.id)
        assertEquals("0:2", ReferenceTrackPicker.pick("en-US", options)?.option?.id)
        assertEquals(true, ReferenceTrackPicker.pick("eng", options)?.sameLanguage)
        assertEquals("0:3", ReferenceTrackPicker.pick("de", options)?.option?.id)
        // No full English track: the German one still times the dialogue better than audio would.
        val noEnglish = ReferenceTrackPicker.pick("en", options.filter { it.id != "0:2" })
        assertEquals("0:3", noEnglish?.option?.id)
        assertEquals("deu", noEnglish?.otherLanguage)
        listOf("0:1", "0:4", "0:5", "0:6").forEach { id -> assertTrue(ReferenceTrackPicker.isPartial(options.first { it.id == id }), id) }
        listOf("fr", null, "und").forEach { language ->
            val pick = ReferenceTrackPicker.pick(language, options)
            assertEquals("0:2", pick?.option?.id, "$language")
            assertEquals(false, pick?.sameLanguage)
        }
        assertNull(ReferenceTrackPicker.pick("en", options.filter { it.id != "0:2" && it.id != "0:3" }), "only partial tracks: use audio")
    }

    @Test
    fun `audio against an addon file with different line durations lands exactly on dialogue onsets`() = runTest {
        val source = FakeSource(this, embedded = null)
        hear(source.speechLog, truth)
        val recorder = Recorder()
        autoSync(source, recorder).start(shifted(retimed(truth, seed = 3), 5_000), referenceTrackId = null)
        advanceTimeBy(45_001)
        assertEquals(5_000L, recorder.detected.firstOrNull()?.second, "the fast lock itself is onset-exact: ${recorder.statuses.takeLast(3)}")
        advanceTimeBy(AutoSyncTiming().refineTimeoutMs)
        assertEquals(5_000L, recorder.detected.last().second)
    }

    @Test
    fun `drift between subtitle and video frame rates is followed after the lock`() = runTest {
        // 23.976 vs 24 fps-like drift, exaggerated: the file runs 0.1 % fast, starting 2 s late.
        val long = conversation(seed = 31, toUs = 2_700_000_000)
        val drifting = long.map { TimedCue(it.startUs + it.startUs / 1_000 + 2_000_000, it.endUs + it.endUs / 1_000 + 2_000_000, it.text) }
        val source = FakeSource(this, embedded = long)
        val recorder = Recorder()
        val sync = autoSync(source, recorder)
        sync.start(drifting, referenceTrackId = "0:1")

        listOf(10, 20, 30).forEach { minute ->
            advanceTimeBy(minute * 60_000L - currentTime)
            val mediaUs = PLAY_FROM_US + currentTime * 1_000
            val localMs = 2_000 + mediaUs / 1_000_000 // the offset where playback is now
            val applied = recorder.detected.last().second
            assertTrue(abs(applied - localMs) <= 300, "at $minute min: applied $applied, local $localMs")
        }
        assertTrue(recorder.detected.size >= 4, "re-centred ${recorder.detected.size - 1} times")
        assertEquals(AutoSyncStatus.Locked(SyncSource.EMBEDDED, refining = false, tracking = true), recorder.statuses.last())
        sync.stop()
        val count = recorder.detected.size
        advanceTimeBy(600_000)
        assertEquals(count, recorder.detected.size, "a stop (e.g. manual nudge) ends tracking")
    }

    @Test
    fun `onset refinement never turns unrelated audio into a lock`() {
        val external = shifted(retimed(truth, seed = 3), 5_000)
        (70 until 90).forEach { seed ->
            val log = SpeechEnergyLog()
            val other = ActivityGrid.fromCues(conversation(seed), 0, 6_000)
            (0 until 6_000).forEach { cell -> log.record(cell.toLong(), if (other.active[cell]) -30f else -60f, if (other.active[cell]) 0.8f else 0.2f) }
            listOf(200, 300, 600, 1_200, 3_000).forEach { window ->
                assertNull(SyncAnalyzer.analyzeVoice(external, log, window).result, "title $seed, ${window / 10} s")
            }
        }
    }

    @Test
    fun `a steady file is never re-centred by tracking`() = runTest {
        val source = FakeSource(this, embedded = truth)
        val recorder = Recorder()
        autoSync(source, recorder).start(shifted(truth, 2_300), referenceTrackId = "0:3")
        advanceTimeBy(480_000)
        assertEquals(listOf(2_300L), recorder.detected.map { it.second }.distinct())
        assertEquals(1, recorder.detected.size)
    }

    @Test
    fun `the speech log window follows the playhead after a seek back`() {
        val log = SpeechEnergyLog()
        (5_000L until 5_100L).forEach { log.record(it, -30f, 0.8f) }
        (100L until 150L).forEach { log.record(it, -30f, 0.8f) } // seeked back to 10 s
        assertEquals(149L, log.latestCell())
    }

    private companion object {
        const val PLAY_FROM_US = 60_000_000L
    }
}
