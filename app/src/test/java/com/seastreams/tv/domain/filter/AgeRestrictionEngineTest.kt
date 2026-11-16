package com.seastreams.tv.domain.filter

import com.seastreams.tv.domain.model.AgeRestriction
import com.seastreams.tv.domain.model.MovieCertification
import com.seastreams.tv.domain.model.TvCertification
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.NullAndEmptySource
import org.junit.jupiter.params.provider.ValueSource

class AgeRestrictionEngineTest {

    private val engine = AgeRestrictionEngine()

    /** Typical young-kids profile: PG / TV-PG ceilings, unrated content disallowed. */
    private val kids = AgeRestriction(
        movieCeiling = MovieCertification.PG,
        tvCeiling = TvCertification.TV_PG,
        allowUnrated = false,
    )
    private val kidsAllowingUnrated = kids.copy(allowUnrated = true)

    private data class Title(val name: String, val rating: String?, val adult: Boolean = false)

    @Nested
    inner class KidsProfileBlocking {

        @ParameterizedTest(name = "blocks {0}")
        @ValueSource(strings = ["PG-13", "R", "NC-17", "TV-14", "TV-MA"])
        fun `blocks ratings above the ceiling`(rating: String) {
            assertFalse(engine.isAllowed(rating, kids))
        }

        @ParameterizedTest(name = "allows {0}")
        @ValueSource(strings = ["G", "PG", "TV-Y", "TV-Y7", "TV-Y7-FV", "TV-G", "TV-PG"])
        fun `allows ratings at or below the ceiling`(rating: String) {
            assertTrue(engine.isAllowed(rating, kids))
        }

        @ParameterizedTest(name = "blocks unrated \"{0}\"")
        @NullAndEmptySource
        @ValueSource(strings = ["   ", "NR", "Not Rated", "UNRATED", "UR", "12A", "FSK 16", "Approved"])
        fun `blocks unrated or unrecognised content when allowUnrated is false`(rating: String?) {
            assertFalse(engine.isAllowed(rating, kids))
        }

        @ParameterizedTest(name = "allows unrated \"{0}\"")
        @NullAndEmptySource
        @ValueSource(strings = ["NR", "Not Rated", "12A"])
        fun `allows unrated content when allowUnrated is true`(rating: String?) {
            assertTrue(engine.isAllowed(rating, kidsAllowingUnrated))
        }

        @ParameterizedTest(name = "still blocks {0}")
        @ValueSource(strings = ["PG-13", "R", "TV-MA"])
        fun `allowUnrated does not unlock rated content above the ceiling`(rating: String) {
            assertFalse(engine.isAllowed(rating, kidsAllowingUnrated))
        }

        @Test
        fun `adult-flagged content is blocked even when rated G or unrated-allowed`() {
            assertFalse(engine.isAllowed("G", kidsAllowingUnrated, isAdultContent = true))
            assertFalse(engine.isAllowed(null, kidsAllowingUnrated, isAdultContent = true))
        }
    }

    @Nested
    inner class Ceilings {

        @Test
        fun `ceilings are inclusive`() {
            val teen = kids.copy(movieCeiling = MovieCertification.PG_13, tvCeiling = TvCertification.TV_14)
            assertTrue(engine.isAllowed("PG-13", teen))
            assertTrue(engine.isAllowed("TV-14", teen))
            assertFalse(engine.isAllowed("R", teen))
            assertFalse(engine.isAllowed("TV-MA", teen))
        }

        @Test
        fun `movie and TV ladders are evaluated independently`() {
            val mixed = kids.copy(movieCeiling = MovieCertification.G, tvCeiling = TvCertification.TV_14)
            assertFalse(engine.isAllowed("PG", mixed))
            assertTrue(engine.isAllowed("TV-14", mixed))
        }

        @Test
        fun `strictest ceilings allow only the lowest rung`() {
            val toddler = AgeRestriction(MovieCertification.G, TvCertification.TV_Y, allowUnrated = false)
            assertTrue(engine.isAllowed("G", toddler))
            assertTrue(engine.isAllowed("TV-Y", toddler))
            assertFalse(engine.isAllowed("TV-Y7", toddler))
            assertFalse(engine.isAllowed("PG", toddler))
        }
    }

    @Nested
    inner class Normalisation {

        @ParameterizedTest(name = "\"{0}\" is parsed like {1}")
        @CsvSource(
            "pg-13, PG-13",
            "PG13, PG-13",
            "' R ', R",
            "Rated R, R",
            "nc17, NC-17",
            "X, NC-17",
            "TVMA, TV-MA",
            "tv-ma, TV-MA",
            "TV_14, TV-14",
            "TV Y7 FV, TV-Y7",
        )
        fun `messy rating strings are normalised before comparison`(raw: String, canonical: String) {
            assertEquals(CertificationParser.parse(canonical), CertificationParser.parse(raw))
            assertTrue(CertificationParser.parse(raw) != ParsedCertification.Unrated)
        }
    }

    @Nested
    inner class AdultProfile {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = ["NC-17", "R", "TV-MA", "NR"])
        fun `adult profiles see everything`(rating: String?) {
            assertTrue(engine.isAllowed(rating, restriction = null))
        }

        @Test
        fun `filter returns the same list for adult profiles`() {
            val items = listOf("R", "TV-MA", null)
            assertEquals(items, engine.filter(items, restriction = null, certificationOf = { it }))
        }
    }

    @Nested
    inner class ListFiltering {

        @Test
        fun `filter strips blocked titles and preserves order`() {
            val catalog = listOf(
                Title("Bluey", "TV-Y"),
                Title("Deadpool", "R"),
                Title("Frozen", "PG"),
                Title("The Boys", "TV-MA"),
                Title("Home Video", null),
                Title("Spider-Verse", "PG-13"),
                Title("Toy Story", "G"),
                Title("Flagged", "G", adult = true),
            )

            val visible = engine.filter(catalog, kids, certificationOf = { it.rating }, isAdultContent = { it.adult })

            assertEquals(listOf("Bluey", "Frozen", "Toy Story"), visible.map { it.name })
        }

        @Test
        fun `filter keeps unrated titles when allowUnrated is true`() {
            val catalog = listOf(Title("Home Video", null), Title("Deadpool", "R"), Title("Frozen", "PG"))

            val visible = engine.filter(catalog, kidsAllowingUnrated, certificationOf = { it.rating })

            assertEquals(listOf("Home Video", "Frozen"), visible.map { it.name })
        }
    }
}
