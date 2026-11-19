package com.seastreams.tv.domain.model

import java.time.LocalDate

/** Season [season], episode [episode] of a show. Ordered by season, then episode. */
data class EpisodeRef(val season: Int, val episode: Int) : Comparable<EpisodeRef> {
    override fun compareTo(other: EpisodeRef): Int =
        compareValuesBy(this, other, EpisodeRef::season, EpisodeRef::episode)
}

/** One regular season of a show (specials excluded, see [TitleDetails.seasons]). */
data class SeasonInfo(val number: Int, val name: String, val episodeCount: Int)

/**
 * Everything the Details screen shows for one title. It only reaches a ViewModel after the profile's
 * age restriction has been checked (CLAUDE.md rule 6).
 */
data class TitleDetails(
    val media: MediaItem,
    /** Director (or a show's creator) first, then up to [MAX_CAST] top-billed cast; at most [MAX_CREDITS] distinct people. */
    val credits: List<CreditPerson>,
    val genreIds: List<Int>,
    /** TMDB relative image paths, stored with watch progress and library rows. */
    val posterPath: String?,
    val backdropPath: String?,
    /** Regular seasons with at least one episode, in order; empty for movies. */
    val seasons: List<SeasonInfo>,
    /** Latest aired episode of a show; `null` for movies or when TMDB doesn't say. */
    val lastAired: EpisodeRef?,
) {
    val ref: MediaRef get() = media.ref

    companion object {
        const val MAX_CAST = 9
        const val MAX_CREDITS = MAX_CAST + 1
    }
}

/**
 * One person in the Details cast grid. [role] is `Director`/`Creator` when [isDirector], otherwise the
 * character name (`null` when TMDB has none). [profileUrl] is `null` when TMDB has no headshot.
 */
data class CreditPerson(
    val id: Int,
    val name: String,
    val role: String?,
    val profileUrl: String?,
    val isDirector: Boolean,
)

/** One episode as listed on the Episodes screen. */
data class Episode(
    val ref: EpisodeRef,
    val title: String,
    val overview: String,
    val airDate: LocalDate?,
    val runtimeMinutes: Int?,
    /** 16:9 still, sized for the episode list. */
    val stillUrl: String?,
)

/** A stream returned by one addon, ready to hand to a player. */
data class StreamSource(
    val addonUrl: String,
    val addonName: String,
    val url: String,
    /** Source label, e.g. `Torrentio · 4k`. */
    val label: String,
    /** File/quality description lines. */
    val description: String,
    val headers: Map<String, String>,
)

/**
 * An enabled addon that can serve streams for the title being resolved (it was asked, whatever it
 * returned). [url] matches [StreamSource.addonUrl]; [name] is its custom name, else the manifest name.
 */
data class StreamAddon(val url: String, val name: String)

/** A subtitle file returned by a subtitle addon. */
data class SubtitleTrack(val url: String, val language: String, val addonName: String)
