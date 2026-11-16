package com.seastreams.tv.domain.model

/** Toggleable streaming-service rows on the Movies/TV Shows tabs (TMDB watch provider ids, PRD §5). */
enum class StreamingProvider(val tmdbProviderId: Int, val displayName: String) {
    NETFLIX(8, "Netflix"),
    AMAZON_PRIME(9, "Amazon Prime"),
    DISNEY_PLUS(337, "Disney+"),
    HBO_MAX(1899, "HBO Max"),
    HULU(15, "Hulu"),
    APPLE_TV_PLUS(350, "Apple TV+"),
    PARAMOUNT_PLUS(531, "Paramount+"),
    PEACOCK(386, "Peacock"),
}
