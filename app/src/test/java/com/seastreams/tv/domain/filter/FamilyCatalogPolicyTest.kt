package com.seastreams.tv.domain.filter

import com.seastreams.tv.domain.model.MediaType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.LocalDate

class FamilyCatalogPolicyTest {

    private val movies = FamilyCatalogPolicy.MOVIES
    private val shows = FamilyCatalogPolicy.SHOWS

    @Test
    fun `movies need at least one allowed genre and no blocked genre in any position`() {
        assertTrue(movies.acceptsGenres(listOf(10751)))
        assertTrue(movies.acceptsGenres(listOf(16, 10751, 35)))
        assertTrue(movies.acceptsGenres(listOf(53)), "Thriller is on the family movie allowlist")
        assertFalse(movies.acceptsGenres(listOf(16, 27)), "blocked secondary genre (Horror)")
        assertFalse(movies.acceptsGenres(listOf(35, 10749)), "blocked secondary genre (Romance)")
        assertFalse(movies.acceptsGenres(listOf(10752, 12)), "blocked primary genre (War)")
        assertFalse(movies.acceptsGenres(listOf(99)), "Documentary only")
        assertFalse(movies.acceptsGenres(listOf(36, 80)), "History + Crime")
        assertFalse(movies.acceptsGenres(emptyList()), "no genres at all")
        assertFalse(movies.acceptsGenres(listOf(10762)), "a TV-only genre is not on the movie allowlist")
    }

    @Test
    fun `shows need at least one allowed genre and no blocked genre in any position`() {
        assertTrue(shows.acceptsGenres(listOf(10762)))
        assertTrue(shows.acceptsGenres(listOf(16, 10759, 10765)))
        assertFalse(shows.acceptsGenres(listOf(10762, 10764)), "Kids + Reality")
        assertFalse(shows.acceptsGenres(listOf(35, 10767)), "Comedy + Talk")
        assertFalse(shows.acceptsGenres(listOf(18, 10766)), "Drama + Soap")
        assertFalse(shows.acceptsGenres(listOf(10763)), "News")
        assertFalse(shows.acceptsGenres(listOf(10768, 10759)), "War & Politics + Action & Adventure")
        assertFalse(shows.acceptsGenres(listOf(80, 9648)), "Crime + Mystery")
        assertFalse(shows.acceptsGenres(listOf(99)), "Documentary")
        assertFalse(shows.acceptsGenres(emptyList()))
        assertFalse(shows.acceptsGenres(listOf(53)), "Thriller is not on the family show allowlist")
    }

    @Test
    fun `movie certifications are limited to G and PG`() {
        assertTrue(movies.acceptsCertification("G"))
        assertTrue(movies.acceptsCertification("PG"))
        assertTrue(movies.acceptsCertification(" pg "))
        listOf("PG-13", "R", "NC-17", "NR", "", "TV-G", null).forEach { cert ->
            assertFalse(movies.acceptsCertification(cert), "$cert must be rejected")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["TV-Y", "TV-Y7", "TV-G", "TV-PG", "tv-pg"])
    fun `show certifications TV-Y through TV-PG are allowed`(cert: String) {
        assertTrue(shows.acceptsCertification(cert))
    }

    @ParameterizedTest
    @ValueSource(strings = ["TV-14", "TV-MA", "NR", "G", "PG", ""])
    fun `show certifications outside TV-Y through TV-PG are rejected`(cert: String) {
        assertFalse(shows.acceptsCertification(cert))
    }

    @Test
    fun `unrated shows are rejected`() {
        assertFalse(shows.acceptsCertification(null))
    }

    @Test
    fun `TMDB ceilings and rule lookup match the PRD`() {
        assertEquals("PG", movies.maxCertification)
        assertEquals("TV-PG", shows.maxCertification)
        assertSame(movies, FamilyCatalogPolicy.rule(MediaType.MOVIE))
        assertSame(shows, FamilyCatalogPolicy.rule(MediaType.TV))
    }

    @Test
    fun `movie window spans the last 90 days and show window 60 days back to 7 ahead`() {
        val today = LocalDate.of(2026, 11, 21)

        assertEquals(DateWindow(LocalDate.of(2026, 8, 23), today), FamilyCatalogPolicy.movieWindow(today))
        assertEquals(DateWindow(LocalDate.of(2026, 9, 22), LocalDate.of(2026, 11, 28)), FamilyCatalogPolicy.showWindow(today))
    }
}
