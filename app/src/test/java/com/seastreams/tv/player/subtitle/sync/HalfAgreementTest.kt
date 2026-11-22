package com.seastreams.tv.player.subtitle.sync

import com.seastreams.tv.player.subtitle.TimedCue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The full tier needs both halves of the window to agree on the offset. */
class HalfAgreementTest {

    private val talk = conversation(seed = 12)
    private val fromUs = 60_000_000L
    private val toUs = 360_000_000L
    private val reference = ReferenceSnapshot(talk.filter { it.startUs >= fromUs && it.endUs <= toUs }, listOf(ObservedSpan(fromUs, toUs)))

    private fun startGrids(external: List<TimedCue>): Pair<ActivityGrid, ActivityGrid> {
        val startCell = cellOf(fromUs)
        val size = (cellOf(toUs) - startCell).toInt()
        val lag = OffsetCorrelator.MAX_LAG_CELLS
        return ActivityGrid.fromCueStarts(reference.intervals, startCell, size, reference.spans) to
            ActivityGrid.fromCueStarts(external, startCell - lag, size + 2 * lag)
    }

    @Test
    fun `a constant offset agrees in both halves and locks on the full tier`() {
        val external = shifted(talk, 3_200)
        val (ref, subs) = startGrids(external)
        assertTrue(HalfAgreement.agree(ref, subs, external, 3_200))
        assertEquals(GateResult(3_200, GateTier.FULL), SyncAnalyzer.analyzeEmbedded(external, reference).result)
    }

    @Test
    fun `a file whose timing jumps mid-window never reaches the full tier`() {
        // Right until 3:30, then 3 s later (a scene the file's release doesn't have).
        val external = talk.map { if (it.startUs < 210_000_000) it else shifted(listOf(it), 3_000).single() }
        val (ref, subs) = startGrids(external)
        assertFalse(HalfAgreement.agree(ref, subs, external, 0))
        assertFalse(HalfAgreement.agree(ref, subs, external, 3_000))
        assertNotEquals(GateTier.FULL, SyncAnalyzer.analyzeEmbedded(external, reference).result?.tier)
    }

    @Test
    fun `a half with too few lines can't vouch for the offset`() {
        val sparse = talk.filter { it.startUs < 200_000_000 }
        val external = shifted(sparse, 1_000)
        val (ref, subs) = startGrids(external)
        assertFalse(HalfAgreement.agree(ref, subs, external, 1_000), "the second half holds no external lines")
    }
}
