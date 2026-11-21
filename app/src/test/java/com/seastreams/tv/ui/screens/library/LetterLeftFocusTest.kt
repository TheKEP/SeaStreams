package com.seastreams.tv.ui.screens.library

import com.seastreams.tv.core.database.entity.LibrarySortOrder
import com.seastreams.tv.domain.model.MediaItem
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.usecase.LibraryEntry
import com.seastreams.tv.domain.usecase.LibraryFilter
import com.seastreams.tv.domain.usecase.LibraryQuery
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Deterministic `Left` from the Library A–Z bar onto a letter's first poster (PRD §7). */
class LetterLeftFocusTest {

    private val letters = LibraryQuery.LETTERS
    private fun bar(letter: Char) = letters.indexOf(letter)

    private val library = listOf(
        entry(1, "2001: A Space Odyssey", added = 10),
        entry(2, "¡Three Amigos!", added = 80),
        entry(3, "Alien", added = 20),
        entry(4, "Avatar", added = 70),
        entry(5, "Bluey", added = 30, type = MediaType.TV),
        entry(6, "The Matrix", added = 60),
        entry(7, "Mad Max", added = 40),
        entry(8, "Zodiac", added = 50),
    )

    private fun indexFor(sort: LibrarySortOrder, filter: LibraryFilter = LibraryFilter.ALL) =
        LibraryQuery.letterIndex(LibraryQuery.apply(library, filter, sort, genreId = null))

    // A-Z display order: 2001, Alien, Avatar, Bluey, Mad Max, The Matrix, Zodiac, ¡Three Amigos!
    private val aToZ = indexFor(LibrarySortOrder.A_TO_Z)

    @Test
    fun `Left on a populated letter targets its first title`() {
        assertEquals(1, letterLeftTarget(aToZ, bar('A')))
        assertEquals(3, letterLeftTarget(aToZ, bar('B')))
        assertEquals(4, letterLeftTarget(aToZ, bar('M')))
        assertEquals(6, letterLeftTarget(aToZ, bar('Z')))
    }

    @Test
    fun `Left on a populated letter matches OK's lookup`() {
        aToZ.forEach { (letter, index) -> assertEquals(index, letterLeftTarget(aToZ, bar(letter)), "$letter") }
    }

    @Test
    fun `Left on # targets the first non A-Z title`() {
        assertEquals(0, letterLeftTarget(aToZ, bar('#')))
    }

    @Test
    fun `a leading article is ignored, as for OK`() {
        val index = LibraryQuery.letterIndex(listOf(entry(1, "Alien"), entry(2, "The Matrix")))
        assertEquals(1, letterLeftTarget(index, bar('M')))
        assertEquals(false, 'T' in index, "The Matrix never files under T")
    }

    @Test
    fun `targets follow the current sort's display order`() {
        // Z-A: ¡Three Amigos!, Zodiac, The Matrix, Mad Max, Bluey, Avatar, Alien, 2001
        val zToA = indexFor(LibrarySortOrder.Z_TO_A)
        assertEquals(0, letterLeftTarget(zToA, bar('#')))
        assertEquals(2, letterLeftTarget(zToA, bar('M')))
        assertEquals(5, letterLeftTarget(zToA, bar('A')))

        // Last Added: ¡Three, Avatar, The Matrix, Zodiac, Mad Max, Bluey, Alien, 2001
        val lastAdded = indexFor(LibrarySortOrder.LAST_ADDED)
        assertEquals(1, letterLeftTarget(lastAdded, bar('A')))
        assertEquals(2, letterLeftTarget(lastAdded, bar('M')))
    }

    @Test
    fun `targets follow the current filter`() {
        val shows = indexFor(LibrarySortOrder.A_TO_Z, LibraryFilter.TV_SHOWS)
        assertEquals(0, letterLeftTarget(shows, bar('B')))
        assertEquals(0, letterLeftTarget(shows, bar('M')), "only Bluey is left, so every letter lands on it")
    }

    @Test
    fun `a dimmed letter targets the nearest populated letter`() {
        assertEquals(3, letterLeftTarget(aToZ, bar('C')), "C is next to B")
        assertEquals(4, letterLeftTarget(aToZ, bar('L')), "L is next to M")
        assertEquals(6, letterLeftTarget(aToZ, bar('Y')), "Y is next to Z")
    }

    @Test
    fun `a tie goes to the following letter`() {
        val index = mapOf('B' to 0, 'D' to 5)
        assertEquals(5, letterLeftTarget(index, bar('C')))
    }

    @Test
    fun `a dimmed # targets the first populated letter and a dimmed Z the nearest above`() {
        val index = mapOf('B' to 0, 'D' to 5, 'M' to 9)
        assertEquals(0, letterLeftTarget(index, bar('#')))
        assertEquals(9, letterLeftTarget(index, bar('Z')))
    }

    @Test
    fun `a single populated letter is every letter's target`() {
        val index = mapOf('Q' to 0)
        letters.indices.forEach { assertEquals(0, letterLeftTarget(index, it), "${letters[it]}") }
    }

    @Test
    fun `no populated letter means no target, so Left is not consumed`() {
        letters.indices.forEach { assertNull(letterLeftTarget(emptyMap(), it)) }
        assertEquals(List(letters.size) { null }, letterLeftTargets(emptyMap()))
    }

    @Test
    fun `out-of-range letters have no target`() {
        assertNull(letterLeftTarget(aToZ, -1))
        assertNull(letterLeftTarget(aToZ, letters.size))
    }

    @Test
    fun `letterLeftTargets covers the whole bar top to bottom`() {
        val targets = letterLeftTargets(aToZ)
        assertEquals(letters.size, targets.size)
        letters.indices.forEach { assertEquals(letterLeftTarget(aToZ, it), targets[it]) }
    }

    private fun entry(id: Int, title: String, added: Long = 0, type: MediaType = MediaType.MOVIE) = LibraryEntry(
        media = MediaItem(
            tmdbId = id, mediaType = type, title = title, overview = "", posterUrl = null, backdropUrl = null,
            heroBackdropUrl = null, logoUrl = null, year = null, ratingPercent = null, genres = emptyList(),
            runtimeMinutes = null, certification = null, isAdultContent = false,
        ),
        genreIds = emptyList(),
        addedAtMillis = added,
        lastWatchedAtMillis = null,
    )
}
