package com.seastreams.tv.domain.usecase

import com.seastreams.tv.core.database.entity.LibrarySortOrder
import com.seastreams.tv.domain.model.MediaItem
import com.seastreams.tv.domain.model.MediaType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LibraryQueryTest {

    private val matrix = entry(1, "The Matrix", MediaType.MOVIE, added = 10, watched = 300, genres = listOf(28))
    private val alien = entry(2, "Alien", MediaType.MOVIE, added = 30, watched = null, genres = listOf(27))
    private val bluey = entry(3, "Bluey", MediaType.TV, added = 20, watched = 500, genres = listOf(16))
    private val numbers = entry(4, "24", MediaType.TV, added = 40, watched = null, genres = listOf(28))
    private val all = listOf(matrix, alien, bluey, numbers)

    private fun titles(sort: LibrarySortOrder, filter: LibraryFilter = LibraryFilter.ALL, genre: Int? = null) =
        LibraryQuery.apply(all, filter, sort, genre).map { it.media.title }

    @Test
    fun `alphabetical sorts ignore a leading article`() {
        assertEquals(listOf("24", "Alien", "Bluey", "The Matrix"), titles(LibrarySortOrder.A_TO_Z))
        assertEquals(listOf("The Matrix", "Bluey", "Alien", "24"), titles(LibrarySortOrder.Z_TO_A))
    }

    @Test
    fun `last added is newest first`() {
        assertEquals(listOf("24", "Alien", "Bluey", "The Matrix"), titles(LibrarySortOrder.LAST_ADDED))
    }

    @Test
    fun `last watched puts recent plays first and unwatched after`() {
        assertEquals(listOf("Bluey", "The Matrix", "24", "Alien"), titles(LibrarySortOrder.LAST_WATCHED))
    }

    @Test
    fun `not watched puts unwatched titles first`() {
        assertEquals(listOf("24", "Alien", "Bluey", "The Matrix"), titles(LibrarySortOrder.NOT_WATCHED))
    }

    @Test
    fun `type and genre filters combine`() {
        assertEquals(listOf("Alien", "The Matrix"), titles(LibrarySortOrder.A_TO_Z, LibraryFilter.MOVIES))
        assertEquals(listOf("24", "Bluey"), titles(LibrarySortOrder.A_TO_Z, LibraryFilter.TV_SHOWS))
        assertEquals(listOf("24", "The Matrix"), titles(LibrarySortOrder.A_TO_Z, genre = 28))
        assertEquals(listOf("The Matrix"), titles(LibrarySortOrder.A_TO_Z, LibraryFilter.MOVIES, genre = 28))
    }

    @Test
    fun `letters file titles by sort key and digits under hash`() {
        assertEquals('M', LibraryQuery.letterOf("The Matrix"))
        assertEquals('#', LibraryQuery.letterOf("24"))
        assertEquals('A', LibraryQuery.letterOf("an Alien"))
        assertEquals('#', LibraryQuery.letterOf(""))
        assertEquals(27, LibraryQuery.LETTERS.size)
    }

    @Test
    fun `letter index points at the first title for each letter in display order`() {
        val ordered = LibraryQuery.apply(all, LibraryFilter.ALL, LibrarySortOrder.A_TO_Z, null)

        assertEquals(mapOf('#' to 0, 'A' to 1, 'B' to 2, 'M' to 3), LibraryQuery.letterIndex(ordered))
    }

    private fun entry(id: Int, title: String, type: MediaType, added: Long, watched: Long?, genres: List<Int>) = LibraryEntry(
        media = MediaItem(
            tmdbId = id, mediaType = type, title = title, overview = "", posterUrl = null, backdropUrl = null,
            heroBackdropUrl = null, logoUrl = null, year = null, ratingPercent = null, genres = emptyList(),
            runtimeMinutes = null, certification = null, isAdultContent = false,
        ),
        genreIds = genres,
        addedAtMillis = added,
        lastWatchedAtMillis = watched,
    )
}
