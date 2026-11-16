package com.seastreams.tv.domain.model

/** US MPA film ratings, ordered from least to most restrictive. */
enum class MovieCertification(val label: String) {
    G("G"),
    PG("PG"),
    PG_13("PG-13"),
    R("R"),
    NC_17("NC-17"),
    ;

    companion object {
        /** Exact display-label lookup (e.g. `"PG-13"`); use `CertificationParser` for messy input. */
        fun fromLabel(label: String): MovieCertification? = entries.firstOrNull { it.label == label }
    }
}

/** US TV Parental Guidelines, ordered from least to most restrictive. */
enum class TvCertification(val label: String) {
    TV_Y("TV-Y"),
    TV_Y7("TV-Y7"),
    TV_G("TV-G"),
    TV_PG("TV-PG"),
    TV_14("TV-14"),
    TV_MA("TV-MA"),
    ;

    companion object {
        /** Exact display-label lookup (e.g. `"TV-Y7"`); use `CertificationParser` for messy input. */
        fun fromLabel(label: String): TvCertification? = entries.firstOrNull { it.label == label }
    }
}

/**
 * Age restriction attached to a Kids profile. Adult profiles carry no restriction (`null`).
 *
 * @property movieCeiling highest MPA rating the profile may see (inclusive).
 * @property tvCeiling highest TV rating the profile may see (inclusive).
 * @property allowUnrated whether items with no recognised US certification may be shown.
 */
data class AgeRestriction(
    val movieCeiling: MovieCertification,
    val tvCeiling: TvCertification,
    val allowUnrated: Boolean,
)
