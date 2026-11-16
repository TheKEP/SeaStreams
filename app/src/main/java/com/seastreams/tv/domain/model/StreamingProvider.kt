package com.seastreams.tv.domain.model

/**
 * Toggleable streaming-service rows on the Movies/TV Shows tabs (TMDB watch provider ids, PRD §5).
 *
 * [tmdbProviderId] is the provider's stable identity; [watchProviderFilter] is the value sent as
 * `with_watch_providers` on `/discover`, which can OR several TMDB ids with `|` when TMDB splits one
 * service across tiers. Toggle preferences are stored by enum name, so the filter can change freely.
 */
enum class StreamingProvider(
    val tmdbProviderId: Int,
    val displayName: String,
    val watchProviderFilter: String = tmdbProviderId.toString(),
) {
    NETFLIX(8, "Netflix"),
    AMAZON_PRIME(9, "Amazon Prime"),
    DISNEY_PLUS(337, "Disney+"),
    HBO_MAX(1899, "HBO Max"),
    HULU(15, "Hulu"),
    APPLE_TV_PLUS(350, "Apple TV+"),

    /** US titles moved to 2303 (Paramount Plus Premium) and 2616 (Paramount+ Essential); 531 alone is near-empty. */
    PARAMOUNT_PLUS(531, "Paramount+", watchProviderFilter = "2303|531|2616"),
    PEACOCK(386, "Peacock"),
}
