package com.seastreams.tv.domain.filter

import com.seastreams.tv.domain.model.MovieCertification
import com.seastreams.tv.domain.model.TvCertification

/** Result of normalising a free-form certification string from TMDB, MDBList or a Stremio addon. */
sealed interface ParsedCertification {
    data class Movie(val rating: MovieCertification) : ParsedCertification
    data class Tv(val rating: TvCertification) : ParsedCertification

    /** Missing, explicitly unrated (`NR`, `Unrated`, …) or not a recognised US rating. */
    data object Unrated : ParsedCertification
}

/** Pure parser that maps messy real-world rating strings onto the US rating ladders. */
object CertificationParser {

    private val MOVIE_ALIASES: Map<String, MovieCertification> = mapOf(
        "G" to MovieCertification.G,
        "PG" to MovieCertification.PG,
        "PG13" to MovieCertification.PG_13,
        "R" to MovieCertification.R,
        "NC17" to MovieCertification.NC_17,
        "X" to MovieCertification.NC_17,
    )

    private val TV_ALIASES: Map<String, TvCertification> = mapOf(
        "TVY" to TvCertification.TV_Y,
        "TVY7" to TvCertification.TV_Y7,
        // TV-Y7-FV ("fantasy violence") sits on the same rung as TV-Y7.
        "TVY7FV" to TvCertification.TV_Y7,
        "TVG" to TvCertification.TV_G,
        "TVPG" to TvCertification.TV_PG,
        "TV14" to TvCertification.TV_14,
        "TVMA" to TvCertification.TV_MA,
    )

    fun parse(raw: String?): ParsedCertification {
        val key = normalise(raw) ?: return ParsedCertification.Unrated
        MOVIE_ALIASES[key]?.let { return ParsedCertification.Movie(it) }
        TV_ALIASES[key]?.let { return ParsedCertification.Tv(it) }
        return ParsedCertification.Unrated
    }

    /** Upper-cases, drops a leading "RATED" and strips every non-alphanumeric character. */
    private fun normalise(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val upper = raw.trim().uppercase()
        val withoutPrefix = upper.removePrefix("RATED").trimStart()
        val compact = withoutPrefix.filter { it.isLetterOrDigit() }
        return compact.ifEmpty { null }
    }
}
