package com.seastreams.tv.domain.usecase

import com.seastreams.tv.core.database.entity.LibrarySortOrder
import com.seastreams.tv.domain.model.MediaType
import java.util.Locale

/** The Library's left-sidebar filter (PRD §7). */
enum class LibraryFilter(val label: String) {
    ALL("All"),
    MOVIES("Movies"),
    TV_SHOWS("TV Shows"),
    ;

    fun matches(type: MediaType): Boolean = when (this) {
        ALL -> true
        MOVIES -> type == MediaType.MOVIE
        TV_SHOWS -> type == MediaType.TV
    }
}

val LibrarySortOrder.label: String
    get() = when (this) {
        LibrarySortOrder.A_TO_Z -> "A-Z"
        LibrarySortOrder.Z_TO_A -> "Z-A"
        LibrarySortOrder.LAST_ADDED -> "Last Added"
        LibrarySortOrder.LAST_WATCHED -> "Last Watched"
        LibrarySortOrder.NOT_WATCHED -> "Not Watched"
    }

/** Pure filtering, sorting and A–Z indexing for the Library grid. */
object LibraryQuery {
    /** Bucket for titles that don't start with a letter on the quick-jump bar. */
    const val OTHER_LETTER = '#'

    /** `#`, then `A`–`Z`: the quick-jump bar, top to bottom. */
    val LETTERS: List<Char> = listOf(OTHER_LETTER) + ('A'..'Z')

    private val LEADING_ARTICLE = Regex("""^(the|a|an)\s+""",RegexOption.IGNORE_CASE)

    /**
     * Entries of the [filter] type (and [genreId], when set) in [sort] order:
     * - `A-Z` / `Z-A`: by title, ignoring a leading "The", "A" or "An".
     * - `Last Added`: newest first.
     * - `Last Watched`: most recently played first; never-played titles follow, A–Z.
     * - `Not Watched`: never-played titles first, then played ones, each A–Z.
     */
    fun apply(entries: List<LibraryEntry>, filter: LibraryFilter, sort: LibrarySortOrder, genreId: Int?): List<LibraryEntry> {
        val visible = entries.filter { filter.matches(it.media.mediaType) && (genreId == null || genreId in it.genreIds) }
        val byTitle = compareBy<LibraryEntry> { it.sortKey }.thenBy { it.media.tmdbId }
        val comparator = when (sort) {
            LibrarySortOrder.A_TO_Z -> byTitle
            LibrarySortOrder.Z_TO_A -> byTitle.reversed()
            LibrarySortOrder.LAST_ADDED -> compareByDescending<LibraryEntry> { it.addedAtMillis }.then(byTitle)
            LibrarySortOrder.LAST_WATCHED ->
                compareByDescending<LibraryEntry> { it.lastWatchedAtMillis ?: Long.MIN_VALUE }.then(byTitle)
            LibrarySortOrder.NOT_WATCHED -> compareBy<LibraryEntry> { it.lastWatchedAtMillis != null }.then(byTitle)
        }
        return visible.sortedWith(comparator)
    }

    /** Lower-cased title without a leading article, used for alphabetical order and the letter bar. */
    fun sortKey(title: String): String = title.trim().replace(LEADING_ARTICLE, "").lowercase(Locale.US)

    /** Quick-jump letter a title files under: its first sort-key letter A–Z, else [OTHER_LETTER]. */
    fun letterOf(title: String): Char = letterFor(sortKey(title))

    /** [letterOf] for an already computed [sortKey]. */
    fun letterFor(sortKey: String): Char {
        val first = sortKey.firstOrNull()?.uppercaseChar() ?: return OTHER_LETTER
        return if (first in 'A'..'Z') first else OTHER_LETTER
    }

    /** For each letter present, the grid index of the first title filed under it (in display order). */
    fun letterIndex(ordered: List<LibraryEntry>): Map<Char, Int> {
        val index = LinkedHashMap<Char, Int>()
        ordered.forEachIndexed { i, entry -> index.getOrPut(entry.letter) { i } }
        return index
    }
}
