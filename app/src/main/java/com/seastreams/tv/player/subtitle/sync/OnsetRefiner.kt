package com.seastreams.tv.player.subtitle.sync

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToLong

/** A fine onset alignment: the offset, the share of cue starts it lines up, and their mean error. */
data class OnsetFit(val offsetMs: Long, val matchedFraction: Double, val meanErrorMs: Double)

/**
 * Fine alignment after the coarse 100 ms correlation (PRD §9 Auto-Sync): when a line *starts* is what
 * two subtitle files and the speech agree on, while how long a line lingers (display padding, merged
 * lines, the speech hangover) differs. Candidate offsets in [STEP_MS] steps within ±[SEARCH_MS] of the
 * coarse lag are scored by how close each external cue start (moved back by the candidate) lands to
 * its nearest reference onset — a Gaussian of the distance, so many small errors average out below the
 * 100 ms cell size. The result is rounded to the HUD's `0.1s` grid.
 */
object OnsetRefiner {

    const val STEP_MS = 50L
    const val SEARCH_MS = 1_500L
    const val SIGMA_MS = 120.0
    const val MATCH_MS = 150L
    const val MIN_MATCHED = 0.6

    /**
     * The refined offset, or `null` when too few cues ([MIN_MATCHED]) land within ±[MATCH_MS] of an
     * onset at the best candidate, in which case the coarse offset stands. Only cue starts that fall
     * inside `[fromUs, toUs)` (the observed reference window) at the coarse offset take part.
     */
    fun refine(referenceOnsetsUs: List<Long>, cueStartsUs: List<Long>, coarseOffsetMs: Long, fromUs: Long, toUs: Long): OnsetFit? {
        val onsets = referenceOnsetsUs.sorted().toLongArray()
        if (onsets.isEmpty()) return null
        val coarseUs = coarseOffsetMs * MICROS_PER_MILLI
        val starts = cueStartsUs.filter { it - coarseUs in fromUs until toUs }
        if (starts.isEmpty()) return null
        var best: Candidate? = null
        var candidate = coarseOffsetMs - SEARCH_MS
        while (candidate <= coarseOffsetMs + SEARCH_MS) {
            val scored = score(onsets, starts, candidate)
            val current = best
            if (current == null || scored.weight > current.weight + EPSILON ||
                (abs(scored.weight - current.weight) <= EPSILON && scored.meanErrorMs < current.meanErrorMs)
            ) {
                best = scored
            }
            candidate += STEP_MS
        }
        val winner = best ?: return null
        if (winner.matchedFraction < MIN_MATCHED) return null
        // Sub-step: the matched starts' mean signed error moves the 50 ms candidate to a continuous estimate.
        val rounded = ((winner.offsetMs + winner.meanSignedErrorMs) / GRID_MS).roundToLong() * GRID_MS
        return OnsetFit(rounded, winner.matchedFraction, winner.meanErrorMs)
    }

    private class Candidate(
        val offsetMs: Long,
        val weight: Double,
        val matchedFraction: Double,
        val meanErrorMs: Double,
        val meanSignedErrorMs: Double,
    )

    private fun score(onsets: LongArray, starts: List<Long>, offsetMs: Long): Candidate {
        val offsetUs = offsetMs * MICROS_PER_MILLI
        var weight = 0.0
        var matched = 0
        var errorMs = 0.0
        var signedMs = 0.0
        starts.forEach { start ->
            val signed = nearestSignedUs(onsets, start - offsetUs) / MICROS_PER_MILLI.toDouble()
            val distanceMs = abs(signed)
            weight += exp(-distanceMs * distanceMs / (2 * SIGMA_MS * SIGMA_MS))
            if (distanceMs <= MATCH_MS) {
                matched++
                errorMs += distanceMs
                signedMs += signed
            }
        }
        val meanError = if (matched == 0) Double.MAX_VALUE else errorMs / matched
        val meanSigned = if (matched == 0) 0.0 else signedMs / matched
        return Candidate(offsetMs, weight, matched.toDouble() / starts.size, meanError, meanSigned)
    }

    /** Signed distance from [timeUs] to the nearest onset: positive when the cue start is late. */
    private fun nearestSignedUs(sorted: LongArray, timeUs: Long): Long {
        var index = sorted.binarySearch(timeUs)
        if (index >= 0) return 0
        index = -index - 1
        val after = if (index < sorted.size) sorted[index] - timeUs else Long.MAX_VALUE
        val before = if (index > 0) timeUs - sorted[index - 1] else Long.MAX_VALUE
        return if (before <= after) before else -after
    }

    private const val MICROS_PER_MILLI = 1_000L
    private const val GRID_MS = 100L
    private const val EPSILON = 1e-9
}
