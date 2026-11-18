package com.seastreams.tv.data.details

import com.seastreams.tv.core.network.ApiEndpoints
import com.seastreams.tv.core.network.TmdbCastMember
import com.seastreams.tv.core.network.TmdbCreator
import com.seastreams.tv.core.network.TmdbCrewMember
import com.seastreams.tv.data.catalog.TmdbImageUrls
import com.seastreams.tv.domain.model.CreditPerson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TitleCreditsTest {

    private val images = TmdbImageUrls(ApiEndpoints())

    private fun actor(id: Int, order: Int, character: String? = "Role $id", profile: String? = "/a$id.jpg") =
        TmdbCastMember(id = id, name = "Actor $id", character = character, profilePath = profile, order = order)

    private val director = TmdbCrewMember(900, "Jane Director", "Director", "/dir.jpg")

    @Test
    fun `director comes first then the top 9 cast by billing order`() {
        val cast = (1..12).map { actor(it, order = it - 1) }.shuffled()

        val people = topCredits(cast, listOf(director), creators = emptyList(), images)

        assertEquals(10, people.size)
        assertEquals(CreditPerson(900, "Jane Director", "Director", "https://image.tmdb.org/t/p/w185/dir.jpg", isDirector = true), people.first())
        assertEquals((1..9).toList(), people.drop(1).map { it.id })
        assertTrue(people.drop(1).none { it.isDirector })
        assertEquals("Role 1", people[1].role)
    }

    @Test
    fun `only the first Director is used and other crew jobs are ignored`() {
        val crew = listOf(
            TmdbCrewMember(1, "A Writer", "Screenplay"),
            TmdbCrewMember(2, "First Director", "Director"),
            TmdbCrewMember(3, "Co Director", "Director"),
        )

        val people = topCredits(listOf(actor(10, 0)), crew, creators = emptyList(), images)

        assertEquals(listOf(2, 10), people.map { it.id })
        assertEquals("Director", people.first().role)
    }

    @Test
    fun `shows without a crew Director fall back to the creator`() {
        val creators = listOf(TmdbCreator(500, "Show Creator", "/c.jpg"), TmdbCreator(501, "Second Creator"))

        val people = topCredits((1..3).map { actor(it, it) }, crew = emptyList(), creators, images)

        assertEquals(CreditPerson(500, "Show Creator", "Creator", "https://image.tmdb.org/t/p/w185/c.jpg", isDirector = true), people.first())
        assertEquals(listOf(1, 2, 3), people.drop(1).map { it.id })
    }

    @Test
    fun `a crew Director wins over the show creator`() {
        val people = topCredits(emptyList(), listOf(director), listOf(TmdbCreator(500, "Show Creator")), images)

        assertEquals(listOf(900), people.map { it.id })
    }

    @Test
    fun `with no director or creator only the 9 top cast appear`() {
        val people = topCredits((1..12).map { actor(it, it) }, crew = emptyList(), creators = emptyList(), images)

        assertEquals((1..9).toList(), people.map { it.id })
        assertFalse(people.any { it.isDirector })
    }

    @Test
    fun `a director who also acts appears once and the 10th billed actor fills the slot`() {
        val cast = listOf(actor(900, order = 2)) + (1..10).map { actor(it, order = if (it < 3) it - 1 else it + 1) }

        val people = topCredits(cast, listOf(director), creators = emptyList(), images)

        assertEquals(10, people.size)
        assertEquals(10, people.map { it.id }.distinct().size)
        assertEquals(1, people.count { it.id == 900 })
        assertTrue(people.first().isDirector)
        assertEquals((1..9).toList(), people.drop(1).map { it.id })
    }

    @Test
    fun `an actor billed twice appears once`() {
        val cast = listOf(actor(1, 0), actor(1, 1, character = "Second role"), actor(2, 2))

        val people = topCredits(cast, crew = emptyList(), creators = emptyList(), images)

        assertEquals(listOf(1, 2), people.map { it.id })
        assertEquals("Role 1", people.first().role)
    }

    @Test
    fun `null or blank profile paths give no portrait url`() {
        val cast = listOf(actor(1, 0, profile = null), actor(2, 1, profile = ""), actor(3, 2, profile = "/x.jpg"))
        val crew = listOf(TmdbCrewMember(900, "No Photo", "Director", profilePath = null))

        val people = topCredits(cast, crew, creators = emptyList(), images)

        assertNull(people[0].profileUrl)
        assertNull(people[1].profileUrl)
        assertNull(people[2].profileUrl)
        assertEquals("https://image.tmdb.org/t/p/w185/x.jpg", people[3].profileUrl)
    }

    @Test
    fun `blank names are skipped and blank characters become null roles`() {
        val cast = listOf(
            TmdbCastMember(id = 1, name = " ", order = 0),
            actor(2, 1, character = "  "),
            actor(3, 2, character = null),
        )
        val crew = listOf(TmdbCrewMember(4, "", "Director"))

        val people = topCredits(cast, crew, creators = emptyList(), images)

        assertEquals(listOf(2, 3), people.map { it.id })
        assertTrue(people.all { it.role == null })
    }

    @Test
    fun `fewer than 9 cast are returned as-is without padding`() {
        assertEquals(emptyList<CreditPerson>(), topCredits(emptyList(), emptyList(), emptyList(), images))
        assertEquals(3, topCredits((1..2).map { actor(it, it) }, listOf(director), emptyList(), images).size)
    }
}
