package com.seastreams.tv.data.details

import com.seastreams.tv.core.network.TmdbEpisode
import com.seastreams.tv.core.network.TmdbMovieDetails
import com.seastreams.tv.core.network.TmdbTvDetails
import com.seastreams.tv.data.catalog.MediaHydrator
import com.seastreams.tv.data.catalog.TmdbImageUrls
import com.seastreams.tv.data.catalog.toMediaItem
import com.seastreams.tv.domain.filter.AgeRestrictionEngine
import com.seastreams.tv.domain.model.AgeRestriction
import com.seastreams.tv.domain.model.Episode
import com.seastreams.tv.domain.model.EpisodeRef
import com.seastreams.tv.domain.model.MediaRef
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.SeasonInfo
import com.seastreams.tv.domain.model.TitleDetails
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** The title is outside the active Kids profile's age ceiling (CLAUDE.md rule 6). */
class RestrictedTitleException : IllegalStateException("This title isn't available on this profile")

/**
 * Data behind the Details and Episodes screens. Every TMDB call goes through [MediaHydrator.withTmdb]
 * (IO dispatcher + the app-wide rate gate) and is HTTP-cached for 12h.
 *
 * Title details use the exact request the catalog rows hydrate with, so opening Details for a title a
 * row already showed is served from the disk cache.
 */
@Singleton
class TitleDetailsRepository @Inject constructor(
    private val hydrator: MediaHydrator,
    private val imageUrls: TmdbImageUrls,
    private val ageEngine: AgeRestrictionEngine,
) {
    private val imdbIds = ConcurrentHashMap<MediaRef, String>()
    private val seasons = ConcurrentHashMap<Pair<Int, Int>, List<Episode>>()

    /** @throws RestrictedTitleException when [restriction] (a Kids profile's) doesn't allow the title. */
    suspend fun load(ref: MediaRef, restriction: AgeRestriction?): TitleDetails {
        val details = hydrator.withTmdb { service ->
            when (ref.mediaType) {
                MediaType.MOVIE -> service.getMovieDetails(ref.tmdbId).toTitleDetails()
                MediaType.TV -> service.getTvDetails(ref.tmdbId).toTitleDetails()
            }
        }
        val media = details.media
        if (!ageEngine.isAllowed(media.certification, restriction, media.isAdultContent)) throw RestrictedTitleException()
        return details
    }

    /**
     * The title's IMDb id (`tt…`) for Stremio addons, or `null` when TMDB has none. This is the app's
     * only `/external_ids` caller and runs only for the single title the user opened — its Details
     * screen, or a Continue Watching card played directly (CLAUDE.md rule 5).
     */
    suspend fun imdbIdForDetails(ref: MediaRef): String? {
        imdbIds[ref]?.let { return it }
        val id = hydrator.withTmdb { it.getExternalIds(ref.mediaType, ref.tmdbId) }.imdbId
            ?.trim()
            ?.takeIf { it.startsWith(IMDB_PREFIX) }
        if (id != null) imdbIds[ref] = id
        return id
    }

    /** Episodes of [seasonNumber], in episode order. */
    suspend fun episodes(tvId: Int, seasonNumber: Int): List<Episode> {
        val key = tvId to seasonNumber
        seasons[key]?.let { return it }
        val episodes = hydrator.withTmdb { it.getSeason(tvId, seasonNumber) }.episodes
            .map { it.toEpisode(seasonNumber) }
            .sortedBy { it.ref.episode }
        seasons[key] = episodes
        return episodes
    }

    /** Ranked YouTube trailer keys to try in turn (best first, at most 3); empty when TMDB lists none. */
    suspend fun trailerKeys(ref: MediaRef): List<String> =
        hydrator.withTmdb { it.getVideos(ref.mediaType, ref.tmdbId) }.youtubeTrailerKeys()

    private fun TmdbMovieDetails.toTitleDetails() = TitleDetails(
        media = toMediaItem(imageUrls),
        credits = topCredits(credits?.cast.orEmpty(), credits?.crew.orEmpty(), creators = emptyList(), imageUrls),
        genreIds = genres.map { it.id },
        posterPath = posterPath,
        backdropPath = backdropPath,
        seasons = emptyList(),
        lastAired = null,
        originalLanguage = originalLanguage,
    )

    private fun TmdbTvDetails.toTitleDetails() = TitleDetails(
        media = toMediaItem(imageUrls),
        credits = topCredits(credits?.cast.orEmpty(), credits?.crew.orEmpty(), createdBy, imageUrls),
        genreIds = genres.map { it.id },
        posterPath = posterPath,
        backdropPath = backdropPath,
        seasons = regularSeasons(seasons.map { SeasonInfo(it.seasonNumber, it.name, it.episodeCount) }),
        lastAired = lastEpisodeToAir?.takeIf { it.seasonNumber > 0 && it.episodeNumber > 0 }
            ?.let { EpisodeRef(it.seasonNumber, it.episodeNumber) },
        originalLanguage = originalLanguage,
    )

    private fun TmdbEpisode.toEpisode(seasonNumber: Int) = Episode(
        ref = EpisodeRef(seasonNumber, episodeNumber),
        title = name.ifBlank { "Episode $episodeNumber" },
        overview = overview,
        airDate = parseDate(airDate),
        runtimeMinutes = runtime?.takeIf { it > 0 },
        stillUrl = imageUrls.still(stillPath),
    )

    private companion object {
        const val IMDB_PREFIX = "tt"
    }
}

/**
 * Seasons the Episodes screen lists: numbered seasons with episodes, in order. Specials (season 0) are
 * only kept when a show has nothing else.
 */
internal fun regularSeasons(all: List<SeasonInfo>): List<SeasonInfo> {
    val withEpisodes = all.filter { it.episodeCount > 0 }.sortedBy { it.number }
    return withEpisodes.filter { it.number > 0 }.ifEmpty { withEpisodes }
}

private fun parseDate(value: String?): LocalDate? = try {
    value?.takeIf { it.isNotBlank() }?.let(LocalDate::parse)
} catch (e: DateTimeParseException) {
    null
}
