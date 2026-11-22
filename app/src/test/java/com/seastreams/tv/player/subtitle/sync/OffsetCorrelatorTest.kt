package com.seastreams.tv.player.subtitle.sync

import com.seastreams.tv.player.subtitle.TimedCue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.random.Random

/** Cross-correlation over ±60.0 s in 0.1 s steps and the two-tier confidence gate (PRD §9 Auto-Sync). */
class OffsetCorrelatorTest {

    private val truth = dialogue(seed = 7)
    private val talk = conversation(seed = 7)
    private val window = 3_000 // 5 minutes of cells
    private val start = 600L // from 60 s in

    /** Gates [reference] (default: the true timing) against [source] running [offsetMs] late. */
    private fun gate(
        offsetMs: Long,
        profile: GateProfile = GateProfile.EMBEDDED,
        cells: Int = window,
        from: Long = start,
        source: List<TimedCue> = truth,
        reference: List<TimedCue> = source,
        noise: Double = 0.0,
    ): GateResult? {
        val external = shifted(source, offsetMs)
        val (clean, subs) = grids(reference, external, from, cells)
        val ref = if (noise > 0) noisy(clean, noise, seed = from.toInt()) else clean
        return gateGrid(ref, subs, external, profile)
    }

    private fun gateGrid(ref: ActivityGrid, subs: ActivityGrid, external: List<TimedCue>, profile: GateProfile): GateResult? {
        val estimate = OffsetCorrelator.estimate(ref, subs) ?: return null
        val evidence = WindowEvidence(
            SyncAnalyzer.cuesInWindow(external, ref, estimate.offsetMs),
            OffsetCorrelator.onsetAgreement(ref, external, estimate.offsetMs),
        )
        return OffsetCorrelator.gate(estimate, profile, evidence)
    }

    @Test
    fun `five minutes of embedded cues give the exact offset on the full tier`() {
        assertEquals(GateResult(2_300, GateTier.FULL), gate(2_300))
        assertEquals(GateResult(-7_800, GateTier.FULL), gate(-7_800))
        assertEquals(GateResult(0, GateTier.FULL), gate(0))
    }

    @Test
    fun `offsets up to the search edge are found and a 61 s shift never is`() {
        assertEquals(59_900L, gate(59_900)?.offsetMs)
        assertEquals(-59_900L, gate(-59_900)?.offsetMs)
        assertNull(gate(61_000))
        assertNull(gate(-61_000))
        assertNull(gate(61_000, cells = 200, source = talk))
    }

    @Test
    fun `an offset beyond 30 s locks on the full tier only`() {
        assertEquals(GateResult(45_000, GateTier.FULL), gate(45_000, source = talk))
        assertEquals(GateResult(-45_000, GateTier.FULL), gate(-45_000, source = talk))
        // 20 s of conversation never locks a far offset (the early tier would be needed).
        assertNull(gate(45_000, cells = 200, source = talk))
        val early = SyncEstimate(31_000, peak = 0.9, secondPeak = 0.2, zScore = 20.0, overlapCells = 300)
        assertNull(OffsetCorrelator.gate(early, GateProfile.EMBEDDED, WindowEvidence(8, 1.0)))
        assertEquals(GateTier.EARLY, OffsetCorrelator.gate(early.copy(offsetMs = 30_000), GateProfile.EMBEDDED, WindowEvidence(8, 1.0))?.tier)
    }

    /**
     * Seconds of data until the first gated result as the window grows by 5 s per poll (15 s → 60 s),
     * from 20 starting points; `null` entries never locked.
     */
    private fun secondsToLock(offsetMs: Long, profile: GateProfile, noise: Double): List<Pair<Int, Long>?> = (0 until 20).map { i ->
        val from = 600L + i * 250L
        (150..600 step 50).firstNotNullOfOrNull { cells ->
            gate(offsetMs, profile, cells, from, talk, noise = noise)?.let { cells / 10 to it.offsetMs }
        }
    }

    @Test
    fun `conversation with an embedded reference locks early, on the exact offset`() {
        val locks = secondsToLock(3_400, GateProfile.EMBEDDED, noise = 0.0)
        val seconds = locks.map { it?.first ?: Int.MAX_VALUE }.sorted()
        assertTrue(seconds[seconds.size / 2] <= 20, "median lock after ${seconds[seconds.size / 2]} s: $seconds")
        assertTrue(seconds[17] <= 45, "90% locked by ${seconds[17]} s: $seconds")
        assertTrue(locks.filterNotNull().size >= 19, "locked ${locks.filterNotNull().size}/20 within 60 s: $seconds")
        assertTrue(locks.filterNotNull().all { it.second == 3_400L })
        assertEquals(-12_100L, gate(-12_100, cells = 400, source = talk, from = 1_100)?.offsetMs)
    }

    /** Embedded-reference grids: cue-start pulses on both sides (what `SyncAnalyzer.analyzeEmbedded` uses). */
    private fun startGrids(reference: List<TimedCue>, external: List<TimedCue>, from: Long, cells: Int): Pair<ActivityGrid, ActivityGrid> {
        val lag = OffsetCorrelator.MAX_LAG_CELLS
        return ActivityGrid.fromCueStarts(reference, from, cells) to ActivityGrid.fromCueStarts(external, from - lag, cells + 2 * lag)
    }

    @Test
    fun `durations differing between authors drag an overlap correlation off the true offset`() {
        val external = shifted(retimed(talk, seed = 4), 5_000)
        val (ref, subs) = grids(talk, external, start, window)
        val overlap = requireNotNull(OffsetCorrelator.estimate(ref, subs))
        assertTrue(overlap.offsetMs != 5_000L || overlap.peak < 0.75, "overlap: ${overlap.offsetMs} @ ${overlap.peak}")
        // Cue starts don't care about durations: exact.
        val (startRef, startSubs) = startGrids(talk, external, start, window)
        assertEquals(5_000L, OffsetCorrelator.estimate(startRef, startSubs)?.offsetMs)
    }

    @Test
    fun `an embedded track timed by another author locks on the exact 5 s offset`() {
        val external = shifted(retimed(talk, seed = 4), 5_000)
        val locks = (0 until 20).map { i ->
            (150..600 step 50).firstNotNullOfOrNull { cells ->
                val (r, s) = startGrids(talk, external, 600L + i * 250L, cells)
                gateGrid(r, s, external, GateProfile.EMBEDDED)?.let { cells / 10 to it.offsetMs }
            }
        }
        val seconds = locks.map { it?.first ?: Int.MAX_VALUE }.sorted()
        assertTrue(locks.filterNotNull().size >= 18, "locked ${locks.filterNotNull().size}/20 within 60 s: $seconds")
        assertTrue(seconds[seconds.size / 2] <= 25, "median lock after ${seconds[seconds.size / 2]} s: $seconds")
        assertTrue(locks.filterNotNull().all { it.second == 5_000L }, "$locks")
    }

    @Test
    fun `cue-start correlation never locks onto another title or a periodic file`() {
        listOf(150, 200, 300, 400, window).forEach { cells ->
            val external = shifted(retimed(talk, seed = 4), 5_000)
            (90 until 110).forEach { seed ->
                val (r, s) = startGrids(conversation(seed), external, start, cells)
                assertNull(gateGrid(r, s, external, GateProfile.EMBEDDED), "title $seed, $cells cells")
            }
            val periodic = (0 until 200).map { TimedCue(it * 4_000_000L, it * 4_000_000L + 2_000_000L, "x") }
            val (r, s) = startGrids(periodic, shifted(periodic, 1_500), start, cells)
            assertNull(gateGrid(r, s, shifted(periodic, 1_500), GateProfile.EMBEDDED), "periodic, $cells cells")
        }
    }

    @Test
    fun `noisy voice activity locks within 0_2 s, mostly within 30 s`() {
        val locks = secondsToLock(4_100, GateProfile.VOICE, noise = 0.15)
        val seconds = locks.map { it?.first ?: Int.MAX_VALUE }.sorted()
        assertTrue(seconds[seconds.size / 2] <= 30, "median lock after ${seconds[seconds.size / 2]} s: $seconds")
        assertTrue(locks.filterNotNull().size >= 18, "locked ${locks.filterNotNull().size}/20: $seconds")
        locks.filterNotNull().forEach { assertTrue(abs(it.second - 4_100) <= 200, "locked at ${it.second}") }
        assertEquals(GateResult(4_100, GateTier.FULL), gate(4_100, GateProfile.VOICE, noise = 0.15))
    }

    @Test
    fun `less than 15 s or fewer than 5 lines is never enough`() {
        assertNull(gate(3_400, cells = 140, source = talk))
        assertNull(gate(3_400, profile = GateProfile.VOICE, cells = 140, source = talk))
        // Slow dialogue: 20 s holds only ~4 lines.
        assertTrue((0 until 10).count { i -> gate(3_400, cells = 200, from = 600L + i * 500L) != null } <= 2)
    }

    @Test
    fun `music intros, noise and other titles never lock, early or full`() {
        listOf(150, 200, 300, 400, window).forEach { cells ->
            val external = shifted(talk, 3_000)
            val (_, subs) = grids(talk, external, start, cells)
            val music = ActivityGrid(start, BooleanArray(cells) { (it / 7) % 5 != 0 }, BooleanArray(cells) { true })
            assertNull(gateGrid(music, subs, external, GateProfile.VOICE), "music, $cells cells")
            repeat(5) { seed ->
                val random = Random(seed)
                val noise = ActivityGrid(start, BooleanArray(cells) { random.nextDouble() < 0.4 }, BooleanArray(cells) { true })
                assertNull(gateGrid(noise, subs, external, GateProfile.VOICE), "noise $seed, $cells cells")
            }
            (90 until 100).forEach { seed ->
                assertNull(gate(3_000, GateProfile.EMBEDDED, cells, source = talk, reference = conversation(seed)), "title $seed, $cells cells")
                assertNull(gate(3_000, GateProfile.VOICE, cells, source = talk, reference = conversation(seed), noise = 0.15), "title $seed audio, $cells cells")
            }
        }
    }

    @Test
    fun `strictly periodic dialogue is ambiguous and rejected on both tiers`() {
        val periodic = (0 until 80).map { TimedCue(it * 4_000_000L, it * 4_000_000L + 2_000_000L, "x") }
        listOf(200, window).forEach { cells ->
            val external = shifted(periodic, 1_500)
            val (ref, subs) = grids(periodic, external, start, cells)
            val estimate = requireNotNull(OffsetCorrelator.estimate(ref, subs))
            assertTrue(estimate.peak / estimate.secondPeak < GateTier.FULL.minPeakRatio, "$cells cells")
            assertNull(gateGrid(ref, subs, external, GateProfile.EMBEDDED))
        }
    }

    @Test
    fun `the cue count and onset agreement decide the tier`() {
        val estimate = SyncEstimate(1_000, peak = 0.9, secondPeak = 0.2, zScore = 20.0, overlapCells = 3_000)
        assertEquals(GateTier.FULL, OffsetCorrelator.gate(estimate, GateProfile.EMBEDDED, WindowEvidence(12, 0.5))?.tier)
        assertNull(OffsetCorrelator.gate(estimate, GateProfile.EMBEDDED, WindowEvidence(40, 0.49)), "shared silence alone never locks")
        assertEquals(GateTier.EARLY, OffsetCorrelator.gate(estimate, GateProfile.EMBEDDED, WindowEvidence(11, 0.8))?.tier)
        assertNull(OffsetCorrelator.gate(estimate, GateProfile.EMBEDDED, WindowEvidence(11, 0.79)), "cue starts don't line up")
        assertEquals(GateTier.EARLY, OffsetCorrelator.gate(estimate, GateProfile.EMBEDDED, WindowEvidence(5, 1.0))?.tier)
        assertNull(OffsetCorrelator.gate(estimate, GateProfile.EMBEDDED, WindowEvidence(4, 1.0)))
    }

    @Test
    fun `the early tier asks for a sharper, less ambiguous peak`() {
        val early = SyncEstimate(1_000, peak = 0.7, secondPeak = 0.1, zScore = 5.0, overlapCells = 300)
        val evidence = WindowEvidence(8, 1.0)
        assertEquals(GateTier.EARLY, OffsetCorrelator.gate(early, GateProfile.VOICE, evidence)?.tier)
        assertNull(OffsetCorrelator.gate(early.copy(peak = 0.45), GateProfile.VOICE, evidence), "peak < 0.5")
        assertNull(OffsetCorrelator.gate(early.copy(secondPeak = 0.5), GateProfile.VOICE, evidence), "ratio < 1.5")
        assertNull(OffsetCorrelator.gate(early.copy(zScore = 3.0), GateProfile.VOICE, evidence), "z < 3.5")
        assertNull(OffsetCorrelator.gate(early.copy(overlapCells = 140), GateProfile.VOICE, evidence), "< 15 s")
        assertNull(OffsetCorrelator.gate(early.copy(atSearchEdge = true), GateProfile.VOICE, evidence))
        assertNull(OffsetCorrelator.gate(null, GateProfile.VOICE, evidence))
        // The full tier still accepts a weaker peak once there's a minute and 12 lines of evidence.
        val full = SyncEstimate(1_000, peak = 0.4, secondPeak = 0.25, zScore = 6.5, overlapCells = 600)
        assertEquals(GateTier.FULL, OffsetCorrelator.gate(full, GateProfile.VOICE, WindowEvidence(12, 0.6))?.tier)
        assertEquals(GateTier.FULL, OffsetCorrelator.gate(full, GateProfile.EMBEDDED, WindowEvidence(12, 0.6))?.tier)
        assertNull(OffsetCorrelator.gate(full.copy(peak = 0.34, secondPeak = 0.2), GateProfile.EMBEDDED, WindowEvidence(12, 0.6)), "below 0.35")
        assertNull(OffsetCorrelator.gate(full.copy(secondPeak = 0.3), GateProfile.EMBEDDED, WindowEvidence(12, 0.6)), "ratio < 1.5")
        assertNull(OffsetCorrelator.gate(full.copy(zScore = 5.9), GateProfile.EMBEDDED, WindowEvidence(12, 0.6)), "z < 6")
    }

    @Test
    fun `onset agreement counts cue starts landing on a reference onset within 0_3 s`() {
        // Reference: speech in cells 10–19 and 40–49 (onsets 10 and 40), all covered.
        val reference = ActivityGrid(0, BooleanArray(60) { it in 10..19 || it in 40..49 }, BooleanArray(60) { true })
        val cues = listOf(TimedCue(1_200_000, 1_800_000, "near 10"), TimedCue(2_800_000, 3_300_000, "nowhere"), TimedCue(4_100_000, 4_900_000, "near 40"))
        assertEquals(2.0 / 3, OffsetCorrelator.onsetAgreement(reference, cues, offsetMs = 0), 1e-9)
        assertEquals(2.0 / 3, OffsetCorrelator.onsetAgreement(reference, cues, offsetMs = 100), 1e-9)
        assertEquals(0.0, OffsetCorrelator.onsetAgreement(reference, emptyList(), offsetMs = 0))
    }

    @Test
    fun `only cues inside the covered window count`() {
        val cues = listOf(TimedCue(1_000_000, 2_000_000, "in"), TimedCue(9_000_000, 9_500_000, "out"), TimedCue(4_200_000, 4_400_000, "moved in"))
        val reference = ActivityGrid(0, BooleanArray(50), BooleanArray(50) { it < 40 })
        // Covered: 0.0–4.0 s. Cue midpoints 1.5 s, 9.25 s and 4.3 s, moved back by the offset.
        assertEquals(1, SyncAnalyzer.cuesInWindow(cues, reference, offsetMs = 0))
        assertEquals(2, SyncAnalyzer.cuesInWindow(cues, reference, offsetMs = 1_500)) // 0.0 s and 2.8 s
        assertEquals(1, SyncAnalyzer.cuesInWindow(cues, reference, offsetMs = 5_300)) // only 9.25 → 3.95 s
    }

    @Test
    fun `the search covers exactly 1201 lags in 0_1 s steps`() {
        assertEquals(600, OffsetCorrelator.MAX_LAG_CELLS)
        assertEquals(100_000L, CELL_US)
        val (ref, subs) = grids(truth, shifted(truth, 100), start, window)
        assertEquals(100L, OffsetCorrelator.estimate(ref, subs)?.offsetMs)
    }
}
