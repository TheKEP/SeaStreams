package com.seastreams.tv.domain.model

/** Identifies one title across MDBList, TMDB and watch history. */
data class MediaRef(val tmdbId: Int, val mediaType: MediaType)

/**
 * A movie or show ready for display on a catalog row or the hero carousel. Instances only reach
 * the UI after the profile's age restriction has been applied (CLAUDE.md rule 6).
 *
 * Image fields are absolute URLs already sized for their slot; any of them may be missing.
 */
data class MediaItem(
    val tmdbId: Int,
    val mediaType: MediaType,
    val title: String,
    val overview: String,
    /** 2:3 poster for collapsed cards. */
    val posterUrl: String?,
    /** 16:9 backdrop for expanded cards. */
    val backdropUrl: String?,
    /** Larger 16:9 backdrop for the full-width hero. */
    val heroBackdropUrl: String?,
    /** Transparent title logo; the UI falls back to [title] text when absent. */
    val logoUrl: String?,
    val year: Int?,
    /** TMDB vote average as a 0–100 percentage; `null` when nobody has voted. */
    val ratingPercent: Int?,
    val genres: List<String>,
    val runtimeMinutes: Int?,
    val certification: String?,
    val isAdultContent: Boolean,
) {
    val ref: MediaRef get() = MediaRef(tmdbId, mediaType)

    /** Stable key for lazy lists. */
    val key: String get() = "${mediaType.name}-$tmdbId"
}

/**
 * A card on the Continue Watching row: a started-but-unfinished movie or episode, or (when [nextUp] is set) the
 * next aired episode of a show whose latest watched episode is finished.
 */
data class ContinueWatchingItem(
    val media: MediaItem,
    /** `0` for movies. */
    val seasonNumber: Int,
    /** `0` for movies. */
    val episodeNumber: Int,
    /** Watched fraction in `0f..1f`; `0f` for Next Up cards (no progress bar). */
    val progress: Float,
    /** Set on Next Up cards only. */
    val nextUp: NextUpMarker? = null,
    /** When the card's title was last active (watched, or its Next Up episode aired); the row sorts on it. */
    val activityAtMillis: Long = 0,
) {
    val isNextUp: Boolean get() = nextUp != null
}

/** What a Next Up card was built from, kept so `Remove` can record exactly what was dismissed. */
data class NextUpMarker(
    /** The show's latest aired episode when the card was built; `null` when TMDB didn't say. */
    val lastAired: EpisodeRef?,
)
