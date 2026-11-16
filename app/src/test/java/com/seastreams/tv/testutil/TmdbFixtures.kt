package com.seastreams.tv.testutil

import com.seastreams.tv.core.database.entity.ProfileEntity
import com.seastreams.tv.core.network.TmdbContentRating
import com.seastreams.tv.core.network.TmdbContentRatings
import com.seastreams.tv.core.network.TmdbCountryReleaseDates
import com.seastreams.tv.core.network.TmdbGenre
import com.seastreams.tv.core.network.TmdbMovieDetails
import com.seastreams.tv.core.network.TmdbReleaseDate
import com.seastreams.tv.core.network.TmdbReleaseDates
import com.seastreams.tv.core.network.TmdbTvDetails
import com.seastreams.tv.domain.model.MovieCertification
import com.seastreams.tv.domain.model.TvCertification

/** TMDB movie details carrying a US theatrical [certification] (`null` = unrated). */
fun movieDetails(id: Int, certification: String?, backdrop: Boolean = true) = TmdbMovieDetails(
    id = id,
    title = "Movie $id",
    overview = "Overview $id",
    posterPath = "/p$id.jpg",
    backdropPath = if (backdrop) "/b$id.jpg" else null,
    releaseDate = "2026-01-01",
    runtime = 99,
    voteAverage = 7.4,
    genres = listOf(TmdbGenre(35, "Comedy")),
    releaseDates = TmdbReleaseDates(
        listOfNotNull(certification?.let { TmdbCountryReleaseDates("US", listOf(TmdbReleaseDate(it, 3))) }),
    ),
)

/** TMDB show details carrying a US TV [rating] (`null` = unrated). */
fun tvDetails(id: Int, rating: String?) = TmdbTvDetails(
    id = id,
    name = "Show $id",
    posterPath = "/p$id.jpg",
    backdropPath = "/b$id.jpg",
    firstAirDate = "2024-05-01",
    episodeRunTime = listOf(22),
    contentRatings = TmdbContentRatings(listOfNotNull(rating?.let { TmdbContentRating("US", it) })),
)

/** Adult profile, or a Kids profile capped at PG / TV-Y7 with unrated titles blocked. */
fun testProfile(id: Long, isKids: Boolean) = ProfileEntity(
    id = id,
    name = if (isKids) "Kid" else "Adult",
    avatarIndex = 0,
    isKids = isKids,
    movieCeiling = if (isKids) MovieCertification.PG else null,
    tvCeiling = if (isKids) TvCertification.TV_Y7 else null,
    allowUnrated = false,
    createdAtMillis = 0,
)
