package com.seastreams.tv.ui.screens.details

import com.seastreams.tv.domain.model.MediaItem
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.ui.screens.details.DetailsBadge.Certification
import com.seastreams.tv.ui.screens.details.DetailsBadge.Duration
import com.seastreams.tv.ui.screens.details.DetailsBadge.Genre
import com.seastreams.tv.ui.screens.details.DetailsBadge.Rating
import com.seastreams.tv.ui.screens.details.DetailsBadge.Year
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DetailsMetadataBadgesTest {

    @Test
    fun `badges run rating, year, certification, duration, then genres`() {
        val badges = detailsBadges(media(genres = listOf("Comedy", "Adventure", "Family")))
        assertEquals(
            listOf(Rating(81), Year(2026), Certification("PG"), Duration("1h 49m"), Genre("Comedy"), Genre("Adventure"), Genre("Family")),
            badges,
        )
    }

    @Test
    fun `at most three genres are shown, after duration`() {
        val badges = detailsBadges(media(genres = listOf("Action", "Adventure", "Science Fiction", "Drama", "Thriller")))
        assertEquals(listOf(Genre("Action"), Genre("Adventure"), Genre("Science Fiction")), badges.filterIsInstance<Genre>())
        assertEquals(MAX_DETAILS_GENRES, badges.count { it is Genre })
        assertEquals(Duration("1h 49m"), badges[badges.indexOfFirst { it is Genre } - 1])
    }

    @Test
    fun `missing fields are skipped and the rest keep their order`() {
        val badges = detailsBadges(
            media(rating = null, year = null, certification = "  ", runtime = null, genres = listOf("Drama")),
        )
        assertEquals(listOf(Genre("Drama")), badges)

        val noYear = detailsBadges(media(year = null, runtime = 0, genres = emptyList()))
        assertEquals(listOf(Rating(81), Certification("PG")), noYear)
    }

    @Test
    fun `blank genres are dropped before the cap`() {
        val badges = detailsBadges(media(genres = listOf(" ", "Animation", "", "Family", "Comedy", "Fantasy")))
        assertEquals(listOf(Genre("Animation"), Genre("Family"), Genre("Comedy")), badges.filterIsInstance<Genre>())
    }

    @Test
    fun `no metadata gives no badges`() {
        assertEquals(emptyList<DetailsBadge>(), detailsBadges(media(null, null, null, null, emptyList())))
    }

    @Test
    fun `duration uses the shared runtime format`() {
        assertEquals(listOf(Duration("2h")), detailsBadges(media(null, null, null, 120, emptyList())))
        assertEquals(listOf(Duration("45m")), detailsBadges(media(null, null, null, 45, emptyList())))
    }

    @Test
    fun `every pill shows when the row fits exactly`() {
        // 100 + 8 + 50 + 8 + 60 = 226
        assertEquals(3, fittingBadgeCount(listOf(100, 50, 60), gap = 8, maxWidth = 226))
    }

    @Test
    fun `the first pill that doesn't fit and everything after it are dropped`() {
        // Rating, year, cert, duration, then genres 90 / 40 / 70 on a 400px row: 80+8+50+8+40+8+60 = 254,
        // + 8 + 90 = 352 fits, + 8 + 40 = 400 fits exactly, + 8 + 70 = 478 overflows.
        assertEquals(6, fittingBadgeCount(listOf(80, 50, 40, 60, 90, 40, 70), gap = 8, maxWidth = 400))
        // A long middle genre stops the row there, even if a later short genre would have fit.
        assertEquals(5, fittingBadgeCount(listOf(80, 50, 40, 60, 90, 300, 10), gap = 8, maxWidth = 400))
    }

    @Test
    fun `the gap is only counted between pills`() {
        assertEquals(1, fittingBadgeCount(listOf(100), gap = 8, maxWidth = 100))
        assertEquals(1, fittingBadgeCount(listOf(100, 1), gap = 8, maxWidth = 108))
        assertEquals(2, fittingBadgeCount(listOf(100, 1), gap = 8, maxWidth = 109))
    }

    @Test
    fun `nothing fits a zero-width row and an empty row fits nothing`() {
        assertEquals(0, fittingBadgeCount(listOf(10, 10), gap = 8, maxWidth = 0))
        assertEquals(0, fittingBadgeCount(emptyList(), gap = 8, maxWidth = 560))
    }

    private fun media(
        rating: Int? = 81,
        year: Int? = 2026,
        certification: String? = "PG",
        runtime: Int? = 109,
        genres: List<String>,
    ) = MediaItem(
        tmdbId = 1,
        mediaType = MediaType.MOVIE,
        title = "Title",
        overview = "",
        posterUrl = null,
        backdropUrl = null,
        heroBackdropUrl = null,
        logoUrl = null,
        year = year,
        ratingPercent = rating,
        genres = genres,
        runtimeMinutes = runtime,
        certification = certification,
        isAdultContent = false,
    )
}
