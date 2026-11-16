package com.seastreams.tv.domain.filter

import com.seastreams.tv.domain.model.AgeRestriction
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pure Kotlin evaluator deciding whether a title may be shown to a profile.
 *
 * Repositories/use cases call this *before* data reaches a ViewModel (CLAUDE.md rule 6), so Kids
 * profiles never receive out-of-range content even when an addon or MDBList returns it.
 *
 * Rules for a restricted profile:
 * - Items flagged adult are always blocked.
 * - MPA ratings are compared against [AgeRestriction.movieCeiling]; TV ratings against
 *   [AgeRestriction.tvCeiling]. The comparison follows the rating string itself, so a TV rating
 *   on a "movie" record is still judged on the TV ladder. Ceilings are inclusive.
 * - Missing/unknown ratings are allowed only when [AgeRestriction.allowUnrated] is true.
 *
 * A `null` restriction denotes an Adult profile, for which everything is allowed.
 */
@Singleton
class AgeRestrictionEngine @Inject constructor() {

    fun isAllowed(
        certification: String?,
        restriction: AgeRestriction?,
        isAdultContent: Boolean = false,
    ): Boolean {
        if (restriction == null) return true
        if (isAdultContent) return false
        return when (val parsed = CertificationParser.parse(certification)) {
            is ParsedCertification.Movie -> parsed.rating <= restriction.movieCeiling
            is ParsedCertification.Tv -> parsed.rating <= restriction.tvCeiling
            ParsedCertification.Unrated -> restriction.allowUnrated
        }
    }

    /** Returns the items permitted for [restriction], preserving their original order. */
    fun <T> filter(
        items: List<T>,
        restriction: AgeRestriction?,
        certificationOf: (T) -> String?,
        isAdultContent: (T) -> Boolean = { false },
    ): List<T> {
        if (restriction == null) return items
        return items.filter { item ->
            isAllowed(certificationOf(item), restriction, isAdultContent(item))
        }
    }
}
