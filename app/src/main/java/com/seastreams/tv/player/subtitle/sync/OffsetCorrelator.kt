package com.seastreams.tv.player.subtitle.sync

import com.seastreams.tv.player.subtitle.TimedCue
import kotlin.math.abs
import kotlin.math.sqrt

/** The best alignment found between a reference and the external subtitle activity. */
data class SyncEstimate(
    /** Sync offset to apply (`+` shows subtitles earlier), in `0.1s` steps. */
    val offsetMs: Long,
    /** Correlation at the best lag (−1..1). */
    val peak: Double,
    /** Best correlation outside the peak's main lobe (another candidate alignment). */
    val secondPeak: Double,
    /** How far the peak stands above the other lags, in standard deviations. */
    val zScore: Double,
    /** Cells compared at the best lag. */
    val overlapCells: Int,
    /** The best lag sits on the `±30.0s` search edge: the true peak may lie beyond it. */
    val atSearchEdge: Boolean = false,
)

/**
 * Peak correlation each reference needs. Embedded and external files of the same film still differ in
 * cue durations, line splits and SDH lines, which keeps the 100 ms overlap Pearson around 0.5–0.7 even
 * at the right offset, so the embedded bar is low; the peak ratio and onset agreement are what reject
 * chance matches.
 */
enum class GateProfile(val fullMinPeak: Double, val earlyMinPeak: Double) {
    /** Embedded same-language subtitle track as the reference. */
    EMBEDDED(fullMinPeak = 0.35, earlyMinPeak = 0.45),

    /** Audio voice-activity detection as the reference. */
    VOICE(fullMinPeak = 0.35, earlyMinPeak = 0.5),
    ;

    fun minPeak(tier: GateTier): Double = if (tier == GateTier.FULL) fullMinPeak else earlyMinPeak
}

/**
 * Confidence tiers. [EARLY] locks on as little as 15 s / 5 cues: with so few cues the correlation peak
 * alone can't tell a true match from a chance one, so it also needs most external cue starts to land on
 * a speech/cue onset of the reference ([minOnsetAgreement]) and a clearly unambiguous peak. [FULL] needs
 * a minute and 12 cues, and confirms or refines an early lock; it still asks for half the cue starts to
 * land on onsets, because long stretches of shared silence (credits, quiet scenes) inflate the
 * correlation on their own.
 */
enum class GateTier(
    val minOverlapCells: Int,
    val minCues: Int,
    val minPeakRatio: Double,
    val minZScore: Double,
    val minOnsetAgreement: Double,
) {
    EARLY(minOverlapCells = 150, minCues = 5, minPeakRatio = 1.5, minZScore = 3.5, minOnsetAgreement = 0.8),
    FULL(minOverlapCells = 600, minCues = 12, minPeakRatio = 1.5, minZScore = 6.0, minOnsetAgreement = 0.5),
}

/** What the compared window holds at the found offset: its cues and how well their starts line up. */
data class WindowEvidence(val cueCount: Int, val onsetAgreement: Double)

/** An estimate that passed the gate, and the strictest tier it passed. */
data class GateResult(val offsetMs: Long, val tier: GateTier)

/**
 * 1D sliding-window cross-correlation of activity grids across `±30.0s` in `0.1s` steps, and the
 * confidence gate deciding whether a result may be applied automatically.
 */
object OffsetCorrelator {

    /** ±30.0 s in 0.1 s cells. */
    const val MAX_LAG_CELLS = 300
    /** A cue start "lands on" a reference onset within ±0.3 s. */
    const val ONSET_TOLERANCE_CELLS = 3

    /** The main lobe is at least ±1.0 s and extends while the score stays above half the peak. */
    const val PEAK_EXCLUSION_CELLS = 10
    const val LOBE_FRACTION = 0.5

    /**
     * Finds the lag where [subtitles] (external cue activity) best matches [reference] (true timing):
     * subtitle cell `t + lag` against reference cell `t`, Pearson-correlated over cells covered in
     * both. `null` when nothing overlaps.
     */
    fun estimate(reference: ActivityGrid, subtitles: ActivityGrid): SyncEstimate? {
        val lags = 2 * MAX_LAG_CELLS + 1
        val scores = DoubleArray(lags)
        val overlaps = IntArray(lags)
        for (index in 0 until lags) {
            val lag = index - MAX_LAG_CELLS
            var n = 0
            var sumR = 0
            var sumS = 0
            var sumRS = 0
            val shift = reference.startCell + lag - subtitles.startCell
            for (t in 0 until reference.size) {
                if (!reference.covered[t]) continue
                val s = t + shift
                if (s < 0 || s >= subtitles.size || !subtitles.covered[s.toInt()]) continue
                n++
                val r = reference.active[t]
                val sub = subtitles.active[s.toInt()]
                if (r) sumR++
                if (sub) sumS++
                if (r && sub) sumRS++
            }
            scores[index] = pearson(n, sumR, sumS, sumRS)
            overlaps[index] = n
        }
        if (overlaps.all { it == 0 }) return null
        val best = scores.indices.maxBy { scores[it] }
        val lobe = mainLobe(scores, best)
        val background = scores.indices.filter { it !in lobe }.map { scores[it] }
        val secondPeak = background.maxOrNull() ?: 0.0
        val mean = background.average().takeUnless { it.isNaN() } ?: 0.0
        val std = sqrt(background.sumOf { (it - mean) * (it - mean) } / background.size.coerceAtLeast(1))
        val z = (scores[best] - mean) / std.coerceAtLeast(MIN_STD)
        return SyncEstimate(
            offsetMs = (best - MAX_LAG_CELLS) * (CELL_US / MICROS_PER_MILLI),
            peak = scores[best],
            secondPeak = secondPeak,
            zScore = z,
            overlapCells = overlaps[best],
            atSearchEdge = best == 0 || best == lags - 1,
        )
    }

    /** Lag indices around [best] belonging to its peak: ±[PEAK_EXCLUSION_CELLS], widened while above half the peak. */
    private fun mainLobe(scores: DoubleArray, best: Int): IntRange {
        val floor = scores[best] * LOBE_FRACTION
        var from = maxOf(0, best - PEAK_EXCLUSION_CELLS)
        var to = minOf(scores.lastIndex, best + PEAK_EXCLUSION_CELLS)
        while (from > 0 && scores[from - 1] > floor) from--
        while (to < scores.lastIndex && scores[to + 1] > floor) to++
        return from..to
    }

    /**
     * The offset to apply and the strictest tier it passed ([GateTier.FULL] before [GateTier.EARLY]), or
     * `null` when the estimate isn't trustworthy enough (too little overlap or dialogue, a weak,
     * ambiguous or out-of-range peak), in which case the current offset is left alone. [evidence]
     * describes the external cues inside the compared (covered) window at the found offset.
     */
    fun gate(estimate: SyncEstimate?, profile: GateProfile, evidence: WindowEvidence): GateResult? {
        if (estimate == null) return null
        val tier = GateTier.entries.reversed().firstOrNull { passes(estimate, profile, evidence, it) } ?: return null
        return GateResult(estimate.offsetMs, tier)
    }

    fun passes(estimate: SyncEstimate, profile: GateProfile, evidence: WindowEvidence, tier: GateTier): Boolean {
        if (estimate.atSearchEdge || evidence.cueCount < tier.minCues || estimate.overlapCells < tier.minOverlapCells) return false
        if (evidence.onsetAgreement < tier.minOnsetAgreement) return false
        if (estimate.peak < profile.minPeak(tier) || estimate.zScore < tier.minZScore) return false
        return estimate.secondPeak <= 0 || estimate.peak / estimate.secondPeak >= tier.minPeakRatio
    }

    /**
     * The share of external cues (start moved back by [offsetMs], inside the covered window) that start
     * within ±[ONSET_TOLERANCE_CELLS] of a reference onset: an active cell after at least two covered
     * inactive ones. 0 when no cue starts in the window.
     */
    fun onsetAgreement(reference: ActivityGrid, cues: List<TimedCue>, offsetMs: Long): Double {
        val onsets = (ONSET_RUN until reference.size).filter { i ->
            reference.active[i] && (1..ONSET_RUN).all { back -> reference.covered[i - back] && !reference.active[i - back] }
        }
        var inside = 0
        var hits = 0
        cues.forEach { cue ->
            val index = cellOf(cue.startUs - offsetMs * MICROS_PER_MILLI) - reference.startCell
            if (index !in 0 until reference.size || !reference.covered[index.toInt()]) return@forEach
            inside++
            if (onsets.any { abs(it - index) <= ONSET_TOLERANCE_CELLS }) hits++
        }
        return if (inside == 0) 0.0 else hits.toDouble() / inside
    }

    private fun pearson(n: Int, sumR: Int, sumS: Int, sumRS: Int): Double {
        if (n == 0) return 0.0
        val varR = n.toDouble() * sumR - sumR.toDouble() * sumR
        val varS = n.toDouble() * sumS - sumS.toDouble() * sumS
        if (varR <= 0 || varS <= 0) return 0.0
        return (n.toDouble() * sumRS - sumR.toDouble() * sumS) / sqrt(varR * varS)
    }

    private const val MIN_STD = 1e-6
    private const val ONSET_RUN = 2
    private const val MICROS_PER_MILLI = 1_000L
}
