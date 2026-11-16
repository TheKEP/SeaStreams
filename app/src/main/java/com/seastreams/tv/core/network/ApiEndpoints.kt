package com.seastreams.tv.core.network

/** Base URLs for remote services. Injected so tests can point at a local server. */
data class ApiEndpoints(
    val tmdbBaseUrl: String = "https://api.themoviedb.org/3",
    val mdbListBaseUrl: String = "https://mdblist.com",
    val tmdbImageBaseUrl: String = "https://image.tmdb.org/t/p",
)
