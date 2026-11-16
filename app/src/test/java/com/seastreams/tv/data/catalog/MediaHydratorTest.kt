package com.seastreams.tv.data.catalog

import com.seastreams.tv.core.network.ApiEndpoints
import com.seastreams.tv.core.network.TmdbApiKeyMissingException
import com.seastreams.tv.core.network.TmdbApiService
import com.seastreams.tv.core.network.TmdbImage
import com.seastreams.tv.core.network.TmdbImages
import com.seastreams.tv.domain.model.MediaRef
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.testutil.movieDetails
import com.seastreams.tv.testutil.tvDetails
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

class MediaHydratorTest {

    private val tmdb = mockk<TmdbApiService>()
    private val hydrator = MediaHydrator(Lazy { tmdb }, TmdbImageUrls(ApiEndpoints()))

    @Test
    fun `movie details map to a display-ready item`() = runTest {
        coEvery { tmdb.getMovieDetails(7) } returns movieDetails(7, "PG-13").copy(
            images = TmdbImages(listOf(TmdbImage("/logo-de.png", "de"), TmdbImage("/logo-en.png", "en"))),
        )

        val item = requireNotNull(hydrator.hydrate(MediaRef(7, MediaType.MOVIE)))

        assertEquals("Movie 7", item.title)
        assertEquals(74, item.ratingPercent)
        assertEquals(2026, item.year)
        assertEquals(99, item.runtimeMinutes)
        assertEquals(listOf("Comedy"), item.genres)
        assertEquals("PG-13", item.certification)
        assertEquals("https://image.tmdb.org/t/p/w342/p7.jpg", item.posterUrl)
        assertEquals("https://image.tmdb.org/t/p/w780/b7.jpg", item.backdropUrl)
        assertEquals("https://image.tmdb.org/t/p/w1280/b7.jpg", item.heroBackdropUrl)
        assertEquals("https://image.tmdb.org/t/p/w500/logo-en.png", item.logoUrl)
    }

    @Test
    fun `show details use the first episode runtime and TV rating`() = runTest {
        coEvery { tmdb.getTvDetails(3) } returns tvDetails(3, "TV-Y7")

        val item = requireNotNull(hydrator.hydrate(MediaRef(3, MediaType.TV)))

        assertEquals(MediaType.TV, item.mediaType)
        assertEquals(22, item.runtimeMinutes)
        assertEquals(2024, item.year)
        assertEquals("TV-Y7", item.certification)
        assertNull(item.ratingPercent, "no votes means no rating, not 0%")
    }

    @Test
    fun `failed lookups are dropped but a missing api key propagates`() = runTest {
        coEvery { tmdb.getMovieDetails(1) } returns movieDetails(1, "G")
        coEvery { tmdb.getMovieDetails(2) } throws IOException("offline")

        val items = hydrator.hydrateAll(listOf(MediaRef(1, MediaType.MOVIE), MediaRef(2, MediaType.MOVIE)))
        assertEquals(listOf(1), items.map { it.tmdbId })

        coEvery { tmdb.getMovieDetails(9) } throws TmdbApiKeyMissingException()
        assertThrows<TmdbApiKeyMissingException> { hydrator.hydrate(MediaRef(9, MediaType.MOVIE)) }
        coVerify(exactly = 0) { tmdb.getExternalIds(any(), any()) }
    }

    @Test
    fun `logo preference is english, then language-neutral, never svg`() {
        assertEquals("/en.png", TmdbImages(listOf(TmdbImage("/x.png", null), TmdbImage("/en.png", "en"))).preferredLogoPath())
        assertEquals("/x.png", TmdbImages(listOf(TmdbImage("/en.svg", "en"), TmdbImage("/x.png", null))).preferredLogoPath())
        assertNull(TmdbImages(listOf(TmdbImage("/fr.png", "fr"))).preferredLogoPath())
    }

    @Test
    fun `rating and year helpers handle edge values`() {
        assertNull(ratingPercent(0.0))
        assertEquals(100, ratingPercent(10.0))
        assertEquals(67, ratingPercent(6.7))
        assertNull(yearOf(""))
        assertNull(yearOf(null))
        assertEquals(1999, yearOf("1999-12-31"))
    }
}
