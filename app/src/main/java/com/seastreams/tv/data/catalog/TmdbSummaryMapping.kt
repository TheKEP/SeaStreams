package com.seastreams.tv.data.catalog

import com.seastreams.tv.core.network.TmdbMovieSummary
import com.seastreams.tv.core.network.TmdbTvSummary
import com.seastreams.tv.domain.model.MediaItem
import com.seastreams.tv.domain.model.MediaType

/**
 * List results (`/discover`, `/trending`, `/search`) carry no runtime, certification or logo; those
 * stay `null` until a title is hydrated through [MediaHydrator]. [genreNames] maps `genre_ids`.
 */
internal fun TmdbMovieSummary.toMediaItem(images: TmdbImageUrls, genreNames: Map<Int, String>) = MediaItem(
    tmdbId = id,
    mediaType = MediaType.MOVIE,
    title = title,
    overview = overview,
    posterUrl = images.poster(posterPath),
    backdropUrl = images.backdrop(backdropPath),
    heroBackdropUrl = images.heroBackdrop(backdropPath),
    logoUrl = null,
    year = yearOf(releaseDate),
    ratingPercent = ratingPercent(voteAverage),
    genres = genreIds.mapNotNull { genreNames[it] },
    runtimeMinutes = null,
    certification = null,
    isAdultContent = adult,
)

internal fun TmdbTvSummary.toMediaItem(images: TmdbImageUrls, genreNames: Map<Int, String>) = MediaItem(
    tmdbId = id,
    mediaType = MediaType.TV,
    title = name,
    overview = overview,
    posterUrl = images.poster(posterPath),
    backdropUrl = images.backdrop(backdropPath),
    heroBackdropUrl = images.heroBackdrop(backdropPath),
    logoUrl = null,
    year = yearOf(firstAirDate),
    ratingPercent = ratingPercent(voteAverage),
    genres = genreIds.mapNotNull { genreNames[it] },
    runtimeMinutes = null,
    certification = null,
    isAdultContent = adult,
)
