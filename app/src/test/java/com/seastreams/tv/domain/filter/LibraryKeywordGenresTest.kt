package com.seastreams.tv.domain.filter

import com.seastreams.tv.domain.filter.LibraryKeywordGenres.CHRISTMAS_GENRE_ID
import com.seastreams.tv.domain.model.MediaType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The Library's keyword genres (PRD §7): `Christmas` = keywords 1441, 207317 or 193048, movies only. */
class LibraryKeywordGenresTest {

    @Test
    fun `each Christmas keyword puts a movie in Christmas`() {
        listOf(1441, 207317, 193048).forEach { keyword ->
            assertEquals(listOf(CHRISTMAS_GENRE_ID), LibraryKeywordGenres.genreIdsFor(MediaType.MOVIE, listOf(9715, keyword)), "$keyword")
        }
    }

    @Test
    fun `other keywords, no keywords and shows never match`() {
        assertEquals(emptyList<Int>(), LibraryKeywordGenres.genreIdsFor(MediaType.MOVIE, listOf(9715, 1442)))
        assertEquals(emptyList<Int>(), LibraryKeywordGenres.genreIdsFor(MediaType.MOVIE, emptyList()))
        assertEquals(emptyList<Int>(), LibraryKeywordGenres.genreIdsFor(MediaType.TV, listOf(207317)))
    }

    @Test
    fun `names, parsing and storage`() {
        assertEquals("Christmas", LibraryKeywordGenres.nameOf(CHRISTMAS_GENRE_ID))
        assertNull(LibraryKeywordGenres.nameOf(18))
        assertEquals(listOf(1, 23), LibraryKeywordGenres.parseIds(" 1, x,,23"))
        assertEquals(emptyList<Int>(), LibraryKeywordGenres.parseIds(null))
        assertEquals("3,1", LibraryKeywordGenres.storedKeywordIds(listOf(3, 1, 3)))
        assertEquals(LibraryKeywordGenres.MAX_STORED_KEYWORDS, LibraryKeywordGenres.storedKeywordIds((1..500).toList()).split(',').size)
    }
}
