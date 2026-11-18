package com.seastreams.tv.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TmdbPage<T>(
    val page: Int = 1,
    val results: List<T> = emptyList(),
    @SerialName("total_pages") val totalPages: Int = 0,
    @SerialName("total_results") val totalResults: Int = 0,
)

@Serializable
data class TmdbMovieSummary(
    val id: Int,
    val title: String = "",
    val overview: String = "",
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    @SerialName("genre_ids") val genreIds: List<Int> = emptyList(),
    val adult: Boolean = false,
)

@Serializable
data class TmdbTvSummary(
    val id: Int,
    val name: String = "",
    val overview: String = "",
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    @SerialName("genre_ids") val genreIds: List<Int> = emptyList(),
    val adult: Boolean = false,
)

/** `/find/{imdb_id}?external_source=imdb_id`: the TMDB titles carrying that IMDb id. */
@Serializable
data class TmdbFindResult(
    @SerialName("movie_results") val movieResults: List<TmdbMovieSummary> = emptyList(),
    @SerialName("tv_results") val tvResults: List<TmdbTvSummary> = emptyList(),
)

@Serializable
data class TmdbGenre(val id: Int, val name: String = "")

/** Response of `/genre/{movie|tv}/list`. */
@Serializable
data class TmdbGenreList(val genres: List<TmdbGenre> = emptyList())

@Serializable
data class TmdbCastMember(
    val id: Int,
    val name: String = "",
    val character: String? = null,
    @SerialName("profile_path") val profilePath: String? = null,
    val order: Int = 0,
)

/** One `credits.crew` entry; Details only reads `job == "Director"`. */
@Serializable
data class TmdbCrewMember(
    val id: Int,
    val name: String = "",
    val job: String = "",
    @SerialName("profile_path") val profilePath: String? = null,
)

@Serializable
data class TmdbCredits(
    val cast: List<TmdbCastMember> = emptyList(),
    val crew: List<TmdbCrewMember> = emptyList(),
)

/** One `created_by` entry of `/tv/{id}`: the Details director slot's fallback for shows. */
@Serializable
data class TmdbCreator(
    val id: Int,
    val name: String = "",
    @SerialName("profile_path") val profilePath: String? = null,
)

@Serializable
data class TmdbReleaseDate(
    val certification: String = "",
    /** 1 Premiere, 2 Limited theatrical, 3 Theatrical, 4 Digital, 5 Physical, 6 TV. */
    val type: Int = 0,
)

@Serializable
data class TmdbCountryReleaseDates(
    @SerialName("iso_3166_1") val countryCode: String,
    @SerialName("release_dates") val releaseDates: List<TmdbReleaseDate> = emptyList(),
)

@Serializable
data class TmdbReleaseDates(val results: List<TmdbCountryReleaseDates> = emptyList())

@Serializable
data class TmdbContentRating(
    @SerialName("iso_3166_1") val countryCode: String,
    val rating: String = "",
)

@Serializable
data class TmdbContentRatings(val results: List<TmdbContentRating> = emptyList())

@Serializable
data class TmdbMovieDetails(
    val id: Int,
    val title: String = "",
    val overview: String = "",
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    val runtime: Int? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    val genres: List<TmdbGenre> = emptyList(),
    val adult: Boolean = false,
    @SerialName("release_dates") val releaseDates: TmdbReleaseDates? = null,
    val credits: TmdbCredits? = null,
    val images: TmdbImages? = null,
) {
    /** US MPA certification, preferring the theatrical release, else the earliest typed release. */
    fun usCertification(): String? =
        releaseDates?.results
            ?.firstOrNull { it.countryCode == US }
            ?.releaseDates
            ?.filter { it.certification.isNotBlank() }
            ?.minByOrNull { if (it.type == THEATRICAL_RELEASE) 0 else it.type }
            ?.certification
            ?.trim()
}

@Serializable
data class TmdbSeasonSummary(
    val id: Int,
    @SerialName("season_number") val seasonNumber: Int,
    val name: String = "",
    @SerialName("episode_count") val episodeCount: Int = 0,
    @SerialName("air_date") val airDate: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
)

@Serializable
data class TmdbTvDetails(
    val id: Int,
    val name: String = "",
    val overview: String = "",
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("episode_run_time") val episodeRunTime: List<Int> = emptyList(),
    @SerialName("number_of_seasons") val numberOfSeasons: Int = 0,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    val genres: List<TmdbGenre> = emptyList(),
    val seasons: List<TmdbSeasonSummary> = emptyList(),
    val adult: Boolean = false,
    @SerialName("content_ratings") val contentRatings: TmdbContentRatings? = null,
    val credits: TmdbCredits? = null,
    @SerialName("created_by") val createdBy: List<TmdbCreator> = emptyList(),
    val images: TmdbImages? = null,
    @SerialName("last_episode_to_air") val lastEpisodeToAir: TmdbEpisodeRef? = null,
) {
    /** US TV Parental Guidelines rating, if TMDB has one. */
    fun usCertification(): String? =
        contentRatings?.results
            ?.firstOrNull { it.countryCode == US }
            ?.rating
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
}

/** One artwork entry from `append_to_response=images`. */
@Serializable
data class TmdbImage(
    @SerialName("file_path") val filePath: String,
    @SerialName("iso_639_1") val language: String? = null,
)

@Serializable
data class TmdbImages(val logos: List<TmdbImage> = emptyList()) {
    /**
     * Best title logo for an English UI: English first, then language-neutral. SVG logos are
     * skipped because the image pipeline only decodes bitmaps.
     */
    fun preferredLogoPath(): String? {
        val raster = logos.filter { !it.filePath.endsWith(".svg", ignoreCase = true) }
        return (raster.firstOrNull { it.language == "en" } ?: raster.firstOrNull { it.language == null })?.filePath
    }
}

@Serializable
data class TmdbExternalIds(
    val id: Int? = null,
    @SerialName("imdb_id") val imdbId: String? = null,
    @SerialName("tvdb_id") val tvdbId: Int? = null,
)

/** Season/episode numbers of one episode, e.g. a show's `last_episode_to_air`. */
@Serializable
data class TmdbEpisodeRef(
    @SerialName("season_number") val seasonNumber: Int = 0,
    @SerialName("episode_number") val episodeNumber: Int = 0,
)

/** One episode from `/tv/{id}/season/{n}`. */
@Serializable
data class TmdbEpisode(
    @SerialName("episode_number") val episodeNumber: Int,
    @SerialName("season_number") val seasonNumber: Int = 0,
    val name: String = "",
    val overview: String = "",
    @SerialName("air_date") val airDate: String? = null,
    val runtime: Int? = null,
    @SerialName("still_path") val stillPath: String? = null,
)

@Serializable
data class TmdbSeasonDetails(
    @SerialName("season_number") val seasonNumber: Int = 0,
    val name: String = "",
    val episodes: List<TmdbEpisode> = emptyList(),
)

/** One entry of `/movie/{id}/videos` or `/tv/{id}/videos`. */
@Serializable
data class TmdbVideo(
    val key: String = "",
    val site: String = "",
    val type: String = "",
    val official: Boolean = false,
)

@Serializable
data class TmdbVideos(val results: List<TmdbVideo> = emptyList()) {
    /** Best YouTube trailer key: official trailers first, then any trailer, then a teaser. */
    fun youtubeTrailerKey(): String? {
        val youtube = results.filter { it.site.equals("YouTube", ignoreCase = true) && it.key.isNotBlank() }
        return (
            youtube.firstOrNull { it.type == "Trailer" && it.official }
                ?: youtube.firstOrNull { it.type == "Trailer" }
                ?: youtube.firstOrNull { it.type == "Teaser" }
            )?.key
    }
}

private const val US = "US"

/** TMDB release type for a wide theatrical release. */
private const val THEATRICAL_RELEASE = 3
