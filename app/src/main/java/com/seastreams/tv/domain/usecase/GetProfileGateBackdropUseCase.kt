package com.seastreams.tv.domain.usecase

import com.seastreams.tv.core.network.TmdbApiService
import com.seastreams.tv.core.network.TmdbMovieSummary
import com.seastreams.tv.data.catalog.MediaHydrator
import com.seastreams.tv.data.catalog.TmdbImageUrls
import com.seastreams.tv.domain.filter.AgeRestrictionEngine
import com.seastreams.tv.domain.model.AgeRestriction
import com.seastreams.tv.domain.model.MediaRef
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.MovieCertification
import com.seastreams.tv.domain.model.TvCertification
import dagger.Lazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.random.Random

/**
 * The featured backdrop on `ProfileSelectScreen` (PRD §2 Child-Safe Profile Gate Artwork, CLAUDE.md
 * rule 6). Anyone, including a child, sees this screen, so the artwork is filtered here before it can
 * reach the ViewModel:
 *
 * - drawn from TMDB's popular movies (pre-filtered by TMDB on certification and genre), never from
 *   any profile's Continue Watching history;
 * - Horror (27), Romance (10749), Thriller (53) and Crime (80) titles are excluded;
 * - the certification must pass [AgeRestrictionEngine] with [gateRestriction]: `PG` / `TV-PG` when any
 *   Kids profile exists, else `PG-13` / `TV-14`, unrated titles never allowed.
 *
 * Certifications come from the title's details (one cached call per candidate, at most
 * [MAX_CERTIFICATION_CHECKS]), not from a batch loop.
 */
class GetProfileGateBackdropUseCase @Inject constructor(
    private val tmdb: Lazy<TmdbApiService>,
    private val hydrator: MediaHydrator,
    private val engine: AgeRestrictionEngine,
    private val imageUrls: TmdbImageUrls,
) {
    /** A safe backdrop URL, or `null` when none qualified (the screen keeps its dark background). */
    suspend operator fun invoke(anyKidsProfile: Boolean, random: Random = Random.Default): String? {
        val restriction = gateRestriction(anyKidsProfile)
        val service = withContext(Dispatchers.IO) { tmdb.get() }
        val popular = service.discoverGateMovies(restriction.movieCeiling.label, EXCLUDED_GENRES).results
        val candidates = gateCandidates(popular).shuffled(random).take(MAX_CERTIFICATION_CHECKS)
        for (candidate in candidates) {
            val item = hydrator.hydrate(MediaRef(candidate.id, MediaType.MOVIE)) ?: continue
            if (engine.isAllowed(item.certification, restriction, item.isAdultContent)) {
                return imageUrls.heroBackdrop(candidate.backdropPath)
            }
        }
        return null
    }

    companion object {
        /** Horror, Romance, Thriller, Crime. */
        val EXCLUDED_GENRES: Set<Int> = setOf(27, 10749, 53, 80)
        const val MAX_CERTIFICATION_CHECKS = 4
        private const val CANDIDATE_POOL = 12

        fun gateRestriction(anyKidsProfile: Boolean): AgeRestriction = if (anyKidsProfile) {
            AgeRestriction(MovieCertification.PG, TvCertification.TV_PG, allowUnrated = false)
        } else {
            AgeRestriction(MovieCertification.PG_13, TvCertification.TV_14, allowUnrated = false)
        }

        /** Titles with a backdrop that aren't adult or in an excluded genre, in popularity order. */
        fun gateCandidates(results: List<TmdbMovieSummary>): List<TmdbMovieSummary> = results
            .filter { !it.adult && it.backdropPath != null && it.genreIds.none(EXCLUDED_GENRES::contains) }
            .take(CANDIDATE_POOL)
    }
}
