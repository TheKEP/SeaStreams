package com.seastreams.tv.ui.screens.library

import com.seastreams.tv.core.database.entity.LibrarySortOrder
import com.seastreams.tv.domain.model.Genre
import com.seastreams.tv.domain.model.MediaItem
import com.seastreams.tv.domain.usecase.LibraryEntry
import com.seastreams.tv.domain.usecase.LibraryFilter
import com.seastreams.tv.domain.usecase.LibraryQuery

/**
 * Everything the Library screen draws for one filter/genre/sort, derived ahead of time off the main
 * thread (PRD §7). Composition only reads these lists. It never filters, sorts or indexes.
 */
data class LibraryView(
    /** The grid, filtered and sorted. */
    val entries: List<LibraryEntry> = emptyList(),
    /** [entries]' posters, in grid order. */
    val posters: List<MediaItem> = emptyList(),
    /** First grid index for each quick-jump letter that has titles. */
    val letterIndex: Map<Char, Int> = emptyMap(),
    /** [letterLeftTargets] of [letterIndex]: the poster `Left` on each bar letter focuses. */
    val letterLeftTargets: List<Int?> = emptyList(),
    /** Genres present under the filter (ignoring the selected genre), by name. */
    val genreOptions: List<Genre> = emptyList(),
    /**
     * The genre narrowing [entries]; `null` when the view is unfiltered, including a selected genre
     * no title under this filter has (kept by the ViewModel, not applied).
     */
    val appliedGenreId: Int? = null,
)

/**
 * The genre-less view of every sidebar filter, built in one pass per library/sort/genre-name change.
 * Switching between `All`, `Movies` and `TV Shows` is then a map lookup; [deriveGenreViews] does the
 * same for a selected genre.
 */
fun deriveLibraryViews(
    all: List<LibraryEntry>,
    sort: LibrarySortOrder,
    genreNames: Map<Int, String>,
): Map<LibraryFilter, LibraryView> = LibraryFilter.entries.associateWith { filter ->
    deriveLibraryView(all, filter, sort, genreId = null, genreOptions = libraryGenreOptions(all, filter, genreNames))
}

/** One filter's view narrowed to [genreId] (`null` = every genre); [genreOptions] are passed through. */
fun deriveLibraryView(
    all: List<LibraryEntry>,
    filter: LibraryFilter,
    sort: LibrarySortOrder,
    genreId: Int?,
    genreOptions: List<Genre>,
): LibraryView {
    val ordered = LibraryQuery.apply(all, filter, sort, genreId)
    val letterIndex = LibraryQuery.letterIndex(ordered)
    return LibraryView(
        entries = ordered,
        posters = ordered.map { it.media },
        letterIndex = letterIndex,
        letterLeftTargets = letterLeftTargets(letterIndex),
        genreOptions = genreOptions,
        appliedGenreId = genreId,
    )
}

/**
 * Every filter's view narrowed to [genreId], so a filter switch with a genre selected is also a map
 * lookup. The genre stays selected across filters (PRD §7): a filter none of whose titles has it keeps
 * its genre-less view from [base] instead of an empty grid. Matching is by genre id, never by name, so
 * a genre whose name hasn't loaded yet still applies.
 */
fun deriveGenreViews(
    all: List<LibraryEntry>,
    sort: LibrarySortOrder,
    genreId: Int,
    base: Map<LibraryFilter, LibraryView>,
): Map<LibraryFilter, LibraryView> = LibraryFilter.entries.associateWith { filter ->
    val genreLess = base.getValue(filter)
    if (genreLess.entries.none { genreId in it.genreIds }) {
        genreLess
    } else {
        deriveLibraryView(all, filter, sort, genreId, genreLess.genreOptions)
    }
}

/** The Genre dropdown's choices under [filter]: genres of its titles that have a name, sorted by name. */
fun libraryGenreOptions(all: List<LibraryEntry>, filter: LibraryFilter, genreNames: Map<Int, String>): List<Genre> =
    all.asSequence()
        .filter { filter.matches(it.media.mediaType) }
        .flatMap { it.genreIds.asSequence() }
        .distinct()
        .mapNotNull { id -> genreNames[id]?.let { Genre(id, it) } }
        .sortedBy { it.name }
        .toList()
