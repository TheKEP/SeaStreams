package com.seastreams.tv.domain.usecase

import com.seastreams.tv.domain.model.CatalogSource
import com.seastreams.tv.domain.model.Genre
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.StreamingProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CatalogSpecsTest {

    @Test
    fun `movies tab is Popular then each enabled provider and nothing else`() {
        val specs = DiscoverCatalogSources.rowSpecs(MediaType.MOVIE, listOf(StreamingProvider.NETFLIX, StreamingProvider.PEACOCK))

        assertEquals(listOf("Popular Movies", "Netflix", "Peacock"), specs.map { it.title })
        assertEquals(CatalogSource.Discover(MediaType.MOVIE), specs[0].source)
        assertEquals(CatalogSource.Discover(MediaType.MOVIE, StreamingProvider.NETFLIX), specs[1].source)
        assert(specs.all { it.source is CatalogSource.Discover })
    }

    @Test
    fun `tv tab uses tv discover and Popular Shows`() {
        val specs = DiscoverCatalogSources.rowSpecs(MediaType.TV, StreamingProvider.entries)

        assertEquals("Popular Shows", specs.first().title)
        assertEquals(1 + StreamingProvider.entries.size, specs.size)
        assert(specs.all { (it.source as CatalogSource.Discover).mediaType == MediaType.TV })
    }

    @Test
    fun `search categories start with Trending Movies then Trending Shows then every movie genre`() {
        val categories = SearchCatalogUseCase.buildCategories(listOf(Genre(28, "Action"), Genre(12, "Adventure")))

        assertEquals(listOf("Trending Movies", "Trending Shows", "Action Movies", "Adventure Movies"), categories.map { it.title })
        assertEquals(SearchCatalogUseCase.TRENDING_MOVIES, categories.first())
        assertEquals(CatalogSource.Discover(MediaType.MOVIE, genreId = 12), categories.last().source)
    }
}
