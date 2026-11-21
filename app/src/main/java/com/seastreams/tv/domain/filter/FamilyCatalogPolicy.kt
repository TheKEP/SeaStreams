package com.seastreams.tv.domain.filter

import com.seastreams.tv.domain.model.MediaType
import java.time.LocalDate

/**
 * The fixed curation of one Kids `Trending Family` row (PRD §3, ARCHITECTURE §9).
 *
 * TMDB is asked to pre-filter with [maxCertification], [includedGenres] (OR) and [excludedGenres], and
 * the loader then re-checks every title itself: [acceptsGenres] on the list result's genre ids, and
 * [acceptsCertification] on the hydrated US certification, on top of the profile's own ceiling in
 * [AgeRestrictionEngine].
 */
data class FamilyRule(
    /** Sent as TMDB `certification.lte`. */
    val maxCertification: String,
    /** The only US certifications the row may show; unrated titles are never allowed. */
    val allowedCertifications: Set<String>,
    /** A title must carry at least one of these genres. */
    val includedGenres: Set<Int>,
    /** A title carrying any of these genres, in any position, is dropped. */
    val excludedGenres: Set<Int>,
) {
    fun acceptsGenres(genreIds: List<Int>): Boolean =
        genreIds.any(includedGenres::contains) && genreIds.none(excludedGenres::contains)

    fun acceptsCertification(certification: String?): Boolean {
        val normalized = certification?.trim()?.uppercase() ?: return false
        return normalized in allowedCertifications
    }
}

/** An inclusive TMDB date filter window (`*.gte` .. `*.lte`). */
data class DateWindow(val from: LocalDate, val to: LocalDate)

object FamilyCatalogPolicy {
    /** `Trending Family Movies`: US `G`/`PG` digital releases. */
    val MOVIES = FamilyRule(
        maxCertification = "PG",
        allowedCertifications = setOf("G", "PG"),
        includedGenres = setOf(28, 12, 16, 35, 18, 10751, 14, 10402, 9648, 878, 10770, 53, 37),
        excludedGenres = setOf(80, 99, 36, 27, 10749, 10752),
    )

    /** `Trending Family Shows`: US `TV-Y` … `TV-PG` returning series on the air. */
    val SHOWS = FamilyRule(
        maxCertification = "TV-PG",
        allowedCertifications = setOf("TV-Y", "TV-Y7", "TV-G", "TV-PG"),
        includedGenres = setOf(10759, 16, 35, 18, 10751, 10762, 9648, 10765),
        excludedGenres = setOf(80, 99, 10763, 10764, 10766, 10767, 10768),
    )

    /** How far back a digital release may be for `Trending Family Movies`. */
    const val MOVIE_RELEASE_LOOKBACK_DAYS = 90L

    /** How far back an episode may have aired for `Trending Family Shows`. */
    const val SHOW_AIR_LOOKBACK_DAYS = 60L

    /** How far ahead an upcoming episode counts for `Trending Family Shows` (TMDB's "On The Air"). */
    const val SHOW_AIR_LOOKAHEAD_DAYS = 7L

    fun rule(mediaType: MediaType): FamilyRule = when (mediaType) {
        MediaType.MOVIE -> MOVIES
        MediaType.TV -> SHOWS
    }

    /** `release_date.gte` / `.lte` for digital releases: the last 90 days up to [today]. */
    fun movieWindow(today: LocalDate): DateWindow = DateWindow(today.minusDays(MOVIE_RELEASE_LOOKBACK_DAYS), today)

    /** `air_date.gte` / `.lte`: 60 days back to 7 days ahead of [today]. */
    fun showWindow(today: LocalDate): DateWindow =
        DateWindow(today.minusDays(SHOW_AIR_LOOKBACK_DAYS), today.plusDays(SHOW_AIR_LOOKAHEAD_DAYS))
}
