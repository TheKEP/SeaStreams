package com.seastreams.tv.domain.model

/** Top-level media kinds shared by TMDB (`movie`/`tv`), MDBList and Stremio (`movie`/`series`). */
enum class MediaType(val tmdbPath: String, val stremioType: String) {
    MOVIE(tmdbPath = "movie", stremioType = "movie"),
    TV(tmdbPath = "tv", stremioType = "series"),
}
