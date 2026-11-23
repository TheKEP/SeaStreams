package com.seastreams.tv.usenet.verify

import com.seastreams.tv.domain.usenet.DamagePolicy

/** The census verdict for a release. */
sealed interface DamageVerdict {
    data object Intact : DamageVerdict

    /** Tolerant policy: [holes] missing articles, all within [HoleFiller]'s limits; they play as filler. */
    data class Repairable(val holes: Int) : DamageVerdict

    data class Broken(val reason: String) : DamageVerdict
}

/**
 * Judges missing articles under the *Damage policy* (PRD §12): **Strict** refuses a release with any missing
 * article; **Tolerant** accepts holes within [HoleFiller.withinLimits] (≤ 4 consecutive, ≤ 64 in all, ≤ 2% of the
 * articles), except a file's first article, which every read of that file needs (size and layout come from it).
 * [missing] maps a file to its missing article indices; [articleCounts] maps each file to its article count.
 */
fun DamagePolicy.judge(missing: Map<Int, List<Int>>, articleCounts: Map<Int, Int>): DamageVerdict {
    val holes = missing.values.sumOf { it.size }
    if (holes == 0) return DamageVerdict.Intact
    val noun = if (holes == 1) "1 article is" else "$holes articles are"
    if (this == DamagePolicy.STRICT) return DamageVerdict.Broken("$noun missing from the release")
    if (missing.values.any { 0 in it }) return DamageVerdict.Broken("The start of a file is missing")
    val total = articleCounts.values.sum()
    return if (HoleFiller.withinLimits(missing, total)) DamageVerdict.Repairable(holes) else DamageVerdict.Broken("$noun missing, too many to repair")
}
