package com.seastreams.tv.ui.screens.library

import com.seastreams.tv.core.database.entity.LibrarySortOrder
import com.seastreams.tv.domain.model.Genre
import com.seastreams.tv.domain.model.MediaItem
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.usecase.LibraryEntry
import com.seastreams.tv.domain.usecase.LibraryFilter
import com.seastreams.tv.domain.usecase.LibraryQuery
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Precomputed Library views (PRD §7): what the grid, A–Z bar and Genre dropdown show per filter. */
class LibraryViewsTest {

    private val names = mapOf(28 to "Action", 16 to "Animation", 35 to "Comedy")

    private val library = listOf(
        libraryEntry(1, "The Matrix", MediaType.MOVIE, added = 30, genres = listOf(28)),
        libraryEntry(2, "Bluey", MediaType.TV, added = 10, watched = 5, genres = listOf(16, 35)),
        libraryEntry(3, "Alien", MediaType.MOVIE, added = 20, genres = listOf(28, 99)),
        libraryEntry(4, "2001: A Space Odyssey", MediaType.MOVIE, added = 40, genres = emptyList()),
        libraryEntry(5, "An Idiot Abroad", MediaType.TV, added = 50, watched = 9, genres = listOf(35)),
    )

    @Test
    fun `every filter's view matches the query for each sort order`() {
        LibrarySortOrder.entries.forEach { sort ->
            val views = deriveLibraryViews(library, sort, names)
            LibraryFilter.entries.forEach { filter ->
                val expected = LibraryQuery.apply(library, filter, sort, genreId = null)
                val view = views.getValue(filter)
                assertEquals(expected, view.entries, "$filter / $sort")
                assertEquals(expected.map { it.media }, view.posters)
                assertEquals(LibraryQuery.letterIndex(expected), view.letterIndex)
                assertEquals(letterLeftTargets(LibraryQuery.letterIndex(expected)), view.letterLeftTargets)
            }
        }
    }

    @Test
    fun `genre options follow the filter's titles, named ones only, sorted by name`() {
        val views = deriveLibraryViews(library, LibrarySortOrder.A_TO_Z, names)

        assertEquals(listOf(Genre(28, "Action"), Genre(16, "Animation"), Genre(35, "Comedy")), views.getValue(LibraryFilter.ALL).genreOptions)
        assertEquals(listOf(Genre(28, "Action")), views.getValue(LibraryFilter.MOVIES).genreOptions)
        assertEquals(listOf(Genre(16, "Animation"), Genre(35, "Comedy")), views.getValue(LibraryFilter.TV_SHOWS).genreOptions)
        assertEquals(emptyList<Genre>(), deriveLibraryViews(library, LibrarySortOrder.A_TO_Z, emptyMap()).getValue(LibraryFilter.ALL).genreOptions)
    }

    @Test
    fun `a genre view narrows the grid and its letters but keeps the filter's genre options`() {
        val options = libraryGenreOptions(library, LibraryFilter.ALL, names)
        val view = deriveLibraryView(library, LibraryFilter.ALL, LibrarySortOrder.A_TO_Z, genreId = 28, genreOptions = options)

        assertEquals(listOf("Alien", "The Matrix"), view.entries.map { it.media.title })
        assertEquals(mapOf('A' to 0, 'M' to 1), view.letterIndex)
        assertEquals(0, view.letterLeftTargets[LibraryQuery.LETTERS.indexOf('#')])
        assertEquals(1, view.letterLeftTargets[LibraryQuery.LETTERS.indexOf('Z')])
        assertEquals(options, view.genreOptions)
    }

    @Test
    fun `entries precompute their sort key and letter once`() {
        val matrix = library[0]
        val idiot = library[4]
        val odyssey = library[3]

        assertEquals("matrix", matrix.sortKey)
        assertEquals('M', matrix.letter)
        assertEquals("idiot abroad", idiot.sortKey)
        assertEquals('I', idiot.letter)
        assertEquals(LibraryQuery.OTHER_LETTER, odyssey.letter)
        library.forEach { assertEquals(LibraryQuery.letterOf(it.media.title), it.letter) }
    }
}

internal fun libraryEntry(
    id: Int,
    title: String,
    type: MediaType,
    added: Long = 0,
    watched: Long? = null,
    genres: List<Int> = emptyList(),
) = LibraryEntry(
    media = MediaItem(
        tmdbId = id, mediaType = type, title = title, overview = "", posterUrl = null, backdropUrl = null,
        heroBackdropUrl = null, logoUrl = null, year = null, ratingPercent = null, genres = emptyList(),
        runtimeMinutes = null, certification = null, isAdultContent = false,
    ),
    genreIds = genres,
    addedAtMillis = added,
    lastWatchedAtMillis = watched,
)
