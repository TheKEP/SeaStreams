package com.seastreams.tv.domain.usecase

import com.seastreams.tv.core.database.entity.CatalogPlacement
import com.seastreams.tv.core.database.entity.CustomCatalogEntity
import com.seastreams.tv.domain.model.CatalogSource
import com.seastreams.tv.domain.model.Genre
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.StreamingProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CatalogSpecsTest {

    @Test
    fun `movies tab is Popular then each enabled provider then custom lists`() {
        val custom = CustomCatalogEntity(id = 4, listUrl = "https://mdblist.com/lists/a/b", title = "Mine",
            placement = CatalogPlacement.MOVIES, sortOrder = 0, addedAtMillis = 0)

        val specs = DiscoverCatalogSources.rowSpecs(
            MediaType.MOVIE,
            listOf(StreamingProvider.NETFLIX, StreamingProvider.PEACOCK),
            listOf(custom),
        )

        assertEquals(listOf("Popular Movies", "Netflix", "Peacock", "Mine"), specs.map { it.title })
        assertEquals(CatalogSource.Discover(MediaType.MOVIE), specs[0].source)
        assertEquals(CatalogSource.Discover(MediaType.MOVIE, StreamingProvider.NETFLIX), specs[1].source)
        assertEquals(CatalogSource.MdbList("https://mdblist.com/lists/a/b"), specs[3].source)
    }

    @Test
    fun `tv tab uses tv discover and Popular Shows`() {
        val specs = DiscoverCatalogSources.rowSpecs(MediaType.TV, StreamingProvider.entries, emptyList())

        assertEquals("Popular Shows", specs.first().title)
        assertEquals(1 + StreamingProvider.entries.size, specs.size)
        assertEquals(CatalogPlacement.TV_SHOWS, DiscoverCatalogSources.placementFor(MediaType.TV))
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
