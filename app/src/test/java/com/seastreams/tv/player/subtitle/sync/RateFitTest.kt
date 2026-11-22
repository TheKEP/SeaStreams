package com.seastreams.tv.player.subtitle.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Frame-rate fitting: PAL-timed files lock with their clock fixed, unit-rate files never get one. */
class RateFitTest {

    private val truth = conversation(seed = 41, toUs = 2_700_000_000)

    /** 90 s of reference observed from 20 min in, where a 4 % stretch has moved lines by ~50 s. */
    private val fromUs = 1_200_000_000L
    private val toUs = 1_290_000_000L
    private val reference = ReferenceSnapshot(truth.filter { it.startUs >= fromUs && it.endUs <= toUs }, listOf(ObservedSpan(fromUs, toUs)))

    private fun embedded(external: List<com.seastreams.tv.player.subtitle.TimedCue>, searchRates: Boolean = true) =
        RateFit.best(SyncRate.UNIT, searchRates) { SyncAnalyzer.analyzeEmbedded(it.apply(external), reference) }

    private fun lockOf(rated: RatedAnalysis): SyncLock {
        val result = requireNotNull(rated.analysis.result) { "no lock: ${rated.analysis.estimate}" }
        return SyncLock(result.offsetMs, rated.rate, SyncSource.EMBEDDED)
    }

    @Test
    fun `a PAL-timed file against an embedded reference locks mid-film with its clock fixed`() {
        val external = onClock(truth, SyncRate.PAL_TO_FILM, 1_500)
        assertNull(SyncAnalyzer.analyzeEmbedded(external, reference).result, "no single offset fits a 4 % stretch")
        val rated = embedded(external)
        assertEquals(1, rated.rate.family, "a 25 fps file on a film-rate video")
        assertEquals(GateTier.FULL, rated.analysis.result?.tier)
        val error = mappingErrorUs(truth, external, lockOf(rated), fromUs, toUs)
        assertTrue(error <= 200_000, "lines shown $error µs off")
    }

    @Test
    fun `the opposite direction is found too`() {
        val external = onClock(truth, SyncRate.FILM_TO_PAL, -800)
        val rated = embedded(external)
        assertEquals(-1, rated.rate.family)
        assertTrue(mappingErrorUs(truth, external, lockOf(rated), fromUs, toUs) <= 200_000)
    }

    @Test
    fun `a unit-rate file keeps the unit rate`() {
        val rated = embedded(shifted(truth, 2_300))
        assertEquals(SyncRate.UNIT, rated.rate)
        assertEquals(GateResult(2_300, GateTier.FULL), rated.analysis.result)
    }

    @Test
    fun `rates are only searched when asked and with a minute of reference`() {
        val external = onClock(truth, SyncRate.PAL_TO_FILM, 1_500)
        assertEquals(SyncRate.UNIT, embedded(external, searchRates = false).rate)
        val short = ReferenceSnapshot(reference.intervals.filter { it.endUs <= fromUs + 50_000_000 }, listOf(ObservedSpan(fromUs, fromUs + 50_000_000)))
        val rated = RateFit.best(SyncRate.UNIT, true) { SyncAnalyzer.analyzeEmbedded(it.apply(external), short) }
        assertEquals(SyncRate.UNIT, rated.rate)
        assertNull(rated.analysis.result)
    }

    @Test
    fun `a PAL-timed file against audio voice activity locks with its clock fixed`() {
        val log = SpeechEnergyLog()
        val speech = ActivityGrid.fromCues(truth, 0, 30_000)
        (cellOf(fromUs) until cellOf(toUs)).forEach { cell ->
            val voiced = speech.active[cell.toInt()]
            log.record(cell, if (voiced) -30f else -60f, if (voiced) 0.8f else 0.2f)
        }
        val external = onClock(truth, SyncRate.PAL_TO_FILM, 1_500)
        val rated = RateFit.best(SyncRate.UNIT, true) { SyncAnalyzer.analyzeVoice(it.apply(external), log, 900) }
        assertEquals(1, rated.rate.family)
        val result = requireNotNull(rated.analysis.result)
        assertTrue(mappingErrorUs(truth, external, SyncLock(result.offsetMs, rated.rate, SyncSource.AUDIO), fromUs, toUs) <= 300_000)
    }

    @Test
    fun `no rate turns unrelated audio into a lock`() {
        listOf(SyncRate.UNIT, SyncRate.PAL_TO_FILM).forEach { fileRate ->
            val external = onClock(truth, fileRate, 2_000)
            (70 until 80).forEach { seed ->
                val log = SpeechEnergyLog()
                val other = ActivityGrid.fromCues(conversation(seed, toUs = 1_500_000_000), 0, 15_000)
                (12_000 until 15_000).forEach { cell -> log.record(cell.toLong(), if (other.active[cell]) -30f else -60f, if (other.active[cell]) 0.8f else 0.2f) }
                listOf(600, 3_000).forEach { window ->
                    val rated = RateFit.best(SyncRate.UNIT, true) { SyncAnalyzer.analyzeVoice(it.apply(external), log, window) }
                    assertNull(rated.analysis.result, "$fileRate file, title $seed, ${window / 10} s: ${rated.rate}")
                }
            }
        }
    }

    @Test
    fun `rates scale cue times about zero and map the playhead back onto the file`() {
        val cue = com.seastreams.tv.player.subtitle.TimedCue(1_000_000_000, 1_002_000_000, "x")
        val onVideo = SyncRate.PAL_TO_FILM.apply(listOf(cue)).single()
        assertEquals(Math.round(1_000_000_000 * 25.0 / 23.976), onVideo.startUs)
        assertEquals(cue.startUs, SyncRate.PAL_TO_FILM.toSubtitleUs(onVideo.startUs))
        assertEquals(listOf(cue), SyncRate.UNIT.apply(listOf(cue)))
        assertEquals(1_234L, SyncRate.UNIT.toSubtitleUs(1_234))
    }
}
