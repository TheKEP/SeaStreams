package com.seastreams.tv.player.subtitle.sync

/** One analysis pass with the external cues on [rate]'s clock. */
data class RatedAnalysis(val rate: SyncRate, val analysis: SyncAnalysis) {
    val peak: Double get() = analysis.estimate?.peak ?: 0.0
}

/**
 * Frame-rate fitting with a unit-rate prior (PRD §9 Auto-Sync). The current rate is analysed first;
 * the other [SyncRate]s are only tried when it is [SyncRate.UNIT], yields nothing gated, rate search is
 * on for this pass, and a minute of reference is covered (a shorter window can't separate a 4 % stretch
 * from an offset). A non-unit rate wins only on the full tier, with a peak at least [MIN_GAIN] above
 * the unit rate's and [RIVAL_RATIO] × the best rate of the opposite direction; otherwise the unit pass
 * is reported. A locked non-unit rate is kept and never re-searched.
 */
object RateFit {

    const val MIN_GAIN = 0.10
    const val RIVAL_RATIO = 1.25

    fun best(current: SyncRate, searchRates: Boolean, pass: (SyncRate) -> SyncAnalysis): RatedAnalysis {
        val first = RatedAnalysis(current, pass(current))
        val searchable = current == SyncRate.UNIT && searchRates && first.analysis.heardCells >= GateTier.FULL.minOverlapCells
        if (!searchable || first.analysis.result != null) return first
        val others = SyncRate.entries.filter { it != SyncRate.UNIT }.map { RatedAnalysis(it, pass(it)) }
        val winner = others.filter { it.analysis.result?.tier == GateTier.FULL }.maxByOrNull { it.peak } ?: return first
        val rival = others.filter { it.rate.family != winner.rate.family }.maxOfOrNull { it.peak } ?: 0.0
        val beatsUnit = winner.peak >= first.peak + MIN_GAIN
        val beatsRival = rival <= 0 || winner.peak >= rival * RIVAL_RATIO
        return if (beatsUnit && beatsRival) winner else first
    }
}
