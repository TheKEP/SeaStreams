package com.seastreams.tv.domain.filter

import com.seastreams.tv.domain.model.MediaType

/**
 * Library genres that TMDB has no genre for, matched by TMDB keyword instead (PRD §7). Their ids are negative, so
 * they can never collide with a TMDB genre id, and they live on `LibraryEntry.genreIds` beside the real genres, so
 * the Library's views, dropdown and dormant-genre behaviour treat them like any other genre.
 */
object LibraryKeywordGenres {

    /** `Christmas`: movies tagged `christmas-party` (1441), `christmas` (207317) or `christmas-spirit` (193048). */
    const val CHRISTMAS_GENRE_ID = -1225
    const val CHRISTMAS_NAME = "Christmas"
    val CHRISTMAS_KEYWORD_IDS: Set<Int> = setOf(1441, 207317, 193048)

    /** Most keyword ids stored per title; TMDB titles rarely carry more than a few dozen. */
    const val MAX_STORED_KEYWORDS = 200

    /** The keyword genres [keywordIds] put a title of [mediaType] in; only movies are matched. */
    fun genreIdsFor(mediaType: MediaType, keywordIds: List<Int>): List<Int> =
        if (mediaType == MediaType.MOVIE && keywordIds.any(CHRISTMAS_KEYWORD_IDS::contains)) listOf(CHRISTMAS_GENRE_ID) else emptyList()

    /** The name of a keyword genre, `null` for any other id. */
    fun nameOf(genreId: Int): String? = if (genreId == CHRISTMAS_GENRE_ID) CHRISTMAS_NAME else null

    /** Comma-separated ids as stored in `library_items`; blanks and non-numbers are skipped. */
    fun parseIds(csv: String?): List<Int> = csv.orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }

    /** [ids] as stored in `library_items.keywordIds`: distinct, at most [MAX_STORED_KEYWORDS]. */
    fun storedKeywordIds(ids: List<Int>): String = ids.distinct().take(MAX_STORED_KEYWORDS).joinToString(",")
}
