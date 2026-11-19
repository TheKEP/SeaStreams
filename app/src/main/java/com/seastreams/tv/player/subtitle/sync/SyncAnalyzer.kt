package com.seastreams.tv.player.subtitle.sync

import com.seastreams.tv.player.subtitle.TimedCue

/**
 * One correlation pass: its gated result (if any), how much reference was covered, [cues] (embedded:
 * reference cues received; audio: external cues in the window at the best offset), and the best
 * ungated candidate with its evidence, for diagnostics.
 */
data class SyncAnalysis(
    val result: GateResult?,
    val heardCells: Int,
    val cues: Int = 0,
    val estimate: SyncEstimate? = null,
    val evidence: WindowEvidence? = null,
) {
    fun status(from: SyncSource, embeddedCues: Int? = null) = AutoSyncStatus.Scanning(
        source = from,
        heardSeconds = heardCells / CELLS_PER_SECOND,
        cues = cues,
        bestOffsetMs = estimate?.offsetMs,
        bestPeak = estimate?.peak,
        onsetPercent = evidence?.let { (it.onsetAgreement * PERCENT).toInt() },
        embeddedCues = embeddedCues,
    )

    private companion object {
        const val CELLS_PER_SECOND = 10
        const val PERCENT = 100
    }
}

/**
 * One auto-sync analysis pass (PRD §9): builds the reference grid (embedded cue starts, or audio speech
 * activity and onsets), correlates it with the external cues over ±30 s, snaps the lag to dialogue
 * onsets ([OnsetRefiner]) and gates it ([OffsetCorrelator]).
 */
object SyncAnalyzer {

    /** Gated offset from the embedded reference's intervals ([windowUs]: only the most recent stretch). */
    fun analyzeEmbedded(cues: List<TimedCue>, full: ReferenceSnapshot, windowUs: Long? = null): SyncAnalysis {
        val reference = windowUs?.let { recentPart(full, it) } ?: full
        if (reference.spans.isEmpty()) return SyncAnalysis(null, 0, cues = reference.intervals.size)
        val startCell = cellOf(reference.spans.minOf { it.startUs })
        val endCell = cellOf(reference.spans.maxOf { it.endUs }) + 1
        // Starts only: an addon file and the disc's track differ in durations, not in when lines begin.
        val grid = ActivityGrid.fromCueStarts(reference.intervals, startCell, (endCell - startCell).toInt(), reference.spans)
        val onsets = reference.intervals.map { it.startUs }
        return correlate(grid, cues, GateProfile.EMBEDDED, onsets, startsOnly = true).copy(cues = reference.intervals.size)
    }

    /** The last [windowUs] of observed reference (by the latest span end). */
    private fun recentPart(reference: ReferenceSnapshot, windowUs: Long): ReferenceSnapshot {
        val end = reference.spans.maxOfOrNull { it.endUs } ?: return reference
        val from = end - windowUs
        val spans = reference.spans.filter { it.endUs > from }.map { ObservedSpan(maxOf(it.startUs, from), it.endUs) }
        return ReferenceSnapshot(reference.intervals.filter { it.startUs >= from }, spans)
    }

    /**
     * Gated offset from the last [windowCells] of logged speech, correlated two ways: speech onsets
     * against cue starts (immune to how long lines linger on screen) and speech activity against cue
     * coverage (steadier when speech has many onsets per line). Either may lock; both pass the full
     * gate. Without a lock, the pass with the stronger candidate is reported.
     */
    fun analyzeVoice(cues: List<TimedCue>, log: SpeechEnergyLog, windowCells: Int): SyncAnalysis {
        val latest = log.latestCell() ?: return SyncAnalysis(null, 0)
        val speech = ActivityGrid.fromSpeech(log.snapshot(latest + 1 - windowCells, latest + 1))
        val onsets = speechOnsetsUs(speech)
        val pulses = ActivityGrid.fromCueStarts(onsets.map { TimedCue(it, it, "") }, speech.startCell, speech.size)
        val byOnsets = correlate(ActivityGrid(speech.startCell, pulses.active, speech.covered), cues, GateProfile.VOICE, onsets, startsOnly = true)
        if (byOnsets.result != null) return byOnsets
        val byCoverage = correlate(speech, cues, GateProfile.VOICE, onsets, startsOnly = false)
        if (byCoverage.result != null) return byCoverage
        return if ((byOnsets.estimate?.peak ?: 0.0) >= (byCoverage.estimate?.peak ?: 0.0)) byOnsets else byCoverage
    }

    /**
     * Speech onsets of a voice-activity grid: an active cell after two covered inactive ones, timed at
     * the cell start (the hangover only lengthens speech ends, so onsets are unbiased).
     */
    fun speechOnsetsUs(grid: ActivityGrid): List<Long> = (2 until grid.size).filter { i ->
        grid.active[i] && (1..2).all { back -> grid.covered[i - back] && !grid.active[i - back] }
    }.map { cellStartUs(grid.startCell + it) }

    /**
     * Correlates [reference] with the external [cues] — their start pulses when [startsOnly], else
     * their coverage (the result's `cues` = external cues in the window) — snaps the coarse lag to
     * [onsetsUs] with [OnsetRefiner], then gates it.
     */
    private fun correlate(
        reference: ActivityGrid,
        cues: List<TimedCue>,
        profile: GateProfile,
        onsetsUs: List<Long>,
        startsOnly: Boolean,
    ): SyncAnalysis {
        val heard = reference.coveredCount
        if (heard < GateTier.EARLY.minOverlapCells) return SyncAnalysis(null, heard)
        val lag = OffsetCorrelator.MAX_LAG_CELLS
        val size = reference.size + 2 * lag
        val subtitles = if (startsOnly) {
            ActivityGrid.fromCueStarts(cues, reference.startCell - lag, size)
        } else {
            ActivityGrid.fromCues(cues, reference.startCell - lag, size)
        }
        val coarse = OffsetCorrelator.estimate(reference, subtitles) ?: return SyncAnalysis(null, heard)
        // Snap to dialogue onsets first: lingering display time biases the coarse lag, and the onset
        // evidence must be judged where the lines really start. Peak, ratio and z stay the coarse ones.
        val fit = OnsetRefiner.refine(
            onsetsUs, cues.map { it.startUs }, coarse.offsetMs, cellStartUs(reference.startCell), cellStartUs(reference.startCell + reference.size),
        )
        val estimate = fit?.let { coarse.copy(offsetMs = it.offsetMs) } ?: coarse
        val evidence = WindowEvidence(
            cueCount = cuesInWindow(cues, reference, estimate.offsetMs),
            onsetAgreement = OffsetCorrelator.onsetAgreement(reference, cues, estimate.offsetMs),
        )
        return SyncAnalysis(OffsetCorrelator.gate(estimate, profile, evidence), heard, evidence.cueCount, estimate, evidence)
    }

    /** External cues whose midpoint, moved by [offsetMs], lands on a covered reference cell. */
    fun cuesInWindow(cues: List<TimedCue>, reference: ActivityGrid, offsetMs: Long): Int = cues.count { cue ->
        val index = cellOf((cue.startUs + cue.endUs) / 2 - offsetMs * MICROS_PER_MILLI) - reference.startCell
        index in 0 until reference.size && reference.covered[index.toInt()]
    }

    private const val MICROS_PER_MILLI = 1_000L
}
