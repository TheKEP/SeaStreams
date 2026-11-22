package com.seastreams.tv.player.subtitle.sync

import com.seastreams.tv.player.subtitle.TimedCue
import kotlin.math.abs

/**
 * Temporal consistency check for [GateTier.FULL] (PRD §9 Auto-Sync): the covered reference window is
 * split into two halves holding equal numbers of covered cells, and each half is correlated on its own
 * within ±[SEARCH_CELLS] of the window's coarse lag. Both must hold at least [MIN_HALF_CUES] external
 * cues and peak within ±[TOLERANCE_MS] of it, away from that local range's edge. A wrong frame rate,
 * a cut that shifts the timing mid-window, or one dense dialogue stretch carrying a chance match all
 * pull the halves apart, while a true constant offset lands both on the same lag.
 */
object HalfAgreement {

    const val TOLERANCE_MS = 400L
    const val SEARCH_CELLS = 30
    const val MIN_HALF_CUES = 5

    /**
     * Whether both halves of [reference] agree with [coarseOffsetMs] (a lag on the 100 ms grid) against
     * [subtitles] (the external grid the coarse lag came from); [cues] count the lines in each half.
     */
    fun agree(reference: ActivityGrid, subtitles: ActivityGrid, cues: List<TimedCue>, coarseOffsetMs: Long): Boolean {
        val split = splitIndex(reference) ?: return false
        val lag = (coarseOffsetMs / CELL_MS).toInt()
        val range = (lag - SEARCH_CELLS)..(lag + SEARCH_CELLS)
        return listOf(0 until split, split until reference.size).all { part ->
            val half = ActivityGrid(reference.startCell, reference.active, BooleanArray(reference.size) { it in part && reference.covered[it] })
            if (SyncAnalyzer.cuesInWindow(cues, half, coarseOffsetMs) < MIN_HALF_CUES) return@all false
            val local = OffsetCorrelator.estimate(half, subtitles, range) ?: return@all false
            !local.atSearchEdge && local.peak > 0 && abs(local.offsetMs - coarseOffsetMs) <= TOLERANCE_MS
        }
    }

    /** The index before which half the covered cells lie, or `null` with fewer than two covered. */
    private fun splitIndex(grid: ActivityGrid): Int? {
        val total = grid.coveredCount
        if (total < 2) return null
        var seen = 0
        for (i in 0 until grid.size) {
            if (grid.covered[i]) seen++
            if (seen * 2 >= total) return i + 1
        }
        return null
    }

    private const val CELL_MS = CELL_US / 1_000L
}
