package com.seastreams.tv.domain.usecase

import com.seastreams.tv.core.database.entity.ProfileEntity
import com.seastreams.tv.core.util.ApplicationScope
import com.seastreams.tv.core.util.SharedLoadCache
import com.seastreams.tv.core.util.orNullUnlessCancelled
import com.seastreams.tv.data.catalog.GenreRepository
import com.seastreams.tv.data.catalog.MediaHydrator
import com.seastreams.tv.data.catalog.TmdbImageUrls
import com.seastreams.tv.data.catalog.toMediaItem
import com.seastreams.tv.domain.model.AgeRestriction
import com.seastreams.tv.domain.model.CatalogSource
import com.seastreams.tv.domain.model.Genre
import com.seastreams.tv.domain.model.MediaItem
import com.seastreams.tv.domain.model.MediaType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/** One entry of the Search sidebar and the grid it shows. */
data class SearchCategory(val id: String, val title: String, val source: CatalogSource)

/** Text-search matches, split like `search-results.jpg`. */
data class SearchResults(val movies: List<MediaItem>, val shows: List<MediaItem>) {
    val isEmpty: Boolean get() = movies.isEmpty() && shows.isEmpty()
}

/**
 * Search page data (PRD §7): the category sidebar, each category's poster grid, and TMDB text search.
 * Everything is age-filtered through [CatalogLoader] before it is returned (CLAUDE.md rule 6).
 * Category grids are kept in memory for [GRID_TTL_MILLIS], so moving back over a category is instant.
 */
@Singleton
class SearchCatalogUseCase @Inject constructor(
    private val loader: CatalogLoader,
    private val hydrator: MediaHydrator,
    private val imageUrls: TmdbImageUrls,
    private val genres: GenreRepository,
    @ApplicationScope scope: CoroutineScope,
    clock: Clock,
) {
    private val gridCache = SharedLoadCache<GridKey, List<MediaItem>>(scope, GRID_TTL_MILLIS, clock::millis)

    /** `Trending Movies`, `Trending Shows`, then every TMDB movie genre (just the first two offline). */
    suspend fun categories(): List<SearchCategory> =
        buildCategories(orNullUnlessCancelled { genres.genres(MediaType.MOVIE) }.orEmpty())

    suspend fun loadCategory(profile: ProfileEntity, category: SearchCategory): List<MediaItem> {
        val restriction = profile.toAgeRestriction()
        return gridCache.get(GridKey(category.source, restriction)) {
            loader.load(category.source, restriction, GRID_CAP).items
        }
    }

    /** First page of TMDB movie and show matches for [query]. */
    suspend fun search(profile: ProfileEntity, query: String): SearchResults = coroutineScope {
        val restriction = profile.toAgeRestriction()
        val movies = async { searchMovies(query, restriction) }
        val shows = async { searchShows(query, restriction) }
        SearchResults(movies.await(), shows.await())
    }

    private suspend fun searchMovies(query: String, restriction: AgeRestriction?): List<MediaItem> {
        val names = genres.names(MediaType.MOVIE)
        val page = hydrator.withTmdb { it.searchMovies(query) }
        return loader.allowed(page.results.map { it.toMediaItem(imageUrls, names) }, restriction)
    }

    private suspend fun searchShows(query: String, restriction: AgeRestriction?): List<MediaItem> {
        val names = genres.names(MediaType.TV)
        val page = hydrator.withTmdb { it.searchTv(query) }
        return loader.allowed(page.results.map { it.toMediaItem(imageUrls, names) }, restriction)
    }

    private data class GridKey(val source: CatalogSource, val restriction: AgeRestriction?)

    companion object {
        /** Posters per category grid: two TMDB pages (8 rows of 5). */
        const val GRID_CAP = 40
        const val GRID_TTL_MILLIS = 10 * 60 * 1000L

        val TRENDING_MOVIES = SearchCategory("trending-movies", "Trending Movies", CatalogSource.Trending(MediaType.MOVIE))
        val TRENDING_SHOWS = SearchCategory("trending-shows", "Trending Shows", CatalogSource.Trending(MediaType.TV))

        /** The fixed trending entries followed by `<Genre> Movies` for each movie genre, in TMDB order. */
        fun buildCategories(movieGenres: List<Genre>): List<SearchCategory> =
            listOf(TRENDING_MOVIES, TRENDING_SHOWS) + movieGenres.map { genre ->
                SearchCategory(
                    id = "genre-${genre.id}",
                    title = "${genre.name} Movies",
                    source = CatalogSource.Discover(MediaType.MOVIE, genreId = genre.id),
                )
            }
    }
}
