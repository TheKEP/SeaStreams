package com.seastreams.tv.domain.usecase

import com.seastreams.tv.core.network.MdbListApiService
import com.seastreams.tv.core.network.MdbListItem
import com.seastreams.tv.core.network.TmdbApiService
import com.seastreams.tv.data.catalog.GenreRepository
import com.seastreams.tv.data.catalog.MediaHydrator
import com.seastreams.tv.data.catalog.TmdbImageUrls
import com.seastreams.tv.data.catalog.toMediaItem
import com.seastreams.tv.domain.filter.AgeRestrictionEngine
import com.seastreams.tv.domain.filter.FamilyCatalogPolicy
import com.seastreams.tv.domain.model.AgeRestriction
import com.seastreams.tv.domain.model.CatalogRow
import com.seastreams.tv.domain.model.CatalogSource
import com.seastreams.tv.domain.model.MediaItem
import com.seastreams.tv.domain.model.MediaRef
import com.seastreams.tv.domain.model.MediaType
import dagger.Lazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads the titles behind any [CatalogSource] for a profile, already age-filtered (CLAUDE.md rule 6):
 * - MDBList exports are bare ids, so every title is hydrated through TMDB details (which carry the
 *   US certification) and filtered as it is collected.
 * - TMDB lists (`/discover`, `/trending`) already carry posters. Adult profiles get them as-is (no
 *   per-title calls); Kids profiles have each title hydrated to learn its certification first.
 * - Kids `Trending Family` rows ([CatalogSource.FamilyTrending]) are TMDB lists too, but each page
 *   is genre-checked against its [FamilyCatalogPolicy] rule first and every title is always hydrated
 *   and checked against the rule's certification allowlist as well as the profile's ceiling.
 * - Stremio addon catalogs are delegated to [AddonCatalogLoader], filtered through [allowed].
 *
 * Every response is HTTP-cached for 6–12h and no `/external_ids` call is ever made here (rule 5).
 */
@Singleton
class CatalogLoader @Inject constructor(
    /** Lazy so the HTTP stack is built on IO, never during ViewModel creation on the main thread. */
    private val mdbList: Lazy<MdbListApiService>,
    private val hydrator: MediaHydrator,
    private val imageUrls: TmdbImageUrls,
    private val genres: GenreRepository,
    private val ageEngine: AgeRestrictionEngine,
    private val addonCatalogs: AddonCatalogLoader,
    /** Dates the Kids family rows' release / air-date windows. */
    private val clock: Clock,
) {
    /** Up to [cap] allowed titles of [source], in source order. */
    suspend fun load(source: CatalogSource, restriction: AgeRestriction?, cap: Int): CatalogRow = when (source) {
        is CatalogSource.MdbList -> collectAllowed(fetchRefs(source.listUrl), restriction, cap)
        is CatalogSource.Discover, is CatalogSource.Trending, is CatalogSource.FamilyTrending -> loadTmdbList(source, restriction, cap)
        is CatalogSource.Addon -> addonCatalogs.load(source, cap) { allowed(it, restriction) }
    }

    /**
     * Keeps the [items] [restriction] allows. Adult profiles (`null`) get [items] unchanged; for Kids
     * each title is hydrated so its certification is known, and unconfirmable titles are dropped.
     */
    suspend fun allowed(items: List<MediaItem>, restriction: AgeRestriction?): List<MediaItem> {
        if (restriction == null) return items
        return hydrator.hydrateAll(items.map { it.ref }).filter { isAllowed(it, restriction) }
    }

    /**
     * Hydrates [refs] in order, keeping titles allowed by [restriction] (and [accept]) until [cap]
     * are collected. Titles stripped by the filter are back-filled from further down the list, up to
     * [MAX_SCAN_FACTOR] × [cap] lookups.
     */
    suspend fun collectAllowed(
        refs: List<MediaRef>,
        restriction: AgeRestriction?,
        cap: Int,
        accept: (MediaItem) -> Boolean = { true },
    ): CatalogRow {
        val accepted = ArrayList<MediaItem>(cap)
        val scanLimit = minOf(refs.size, cap * MAX_SCAN_FACTOR)
        var scanned = 0
        while (accepted.size < cap && scanned < scanLimit) {
            val batch = refs.subList(scanned, minOf(scanned + cap - accepted.size, scanLimit))
            scanned += batch.size
            accepted += hydrator.hydrateAll(batch).filter { item -> accept(item) && isAllowed(item, restriction) }
        }
        return CatalogRow(items = accepted.take(cap), hasMore = accepted.size > cap || scanned < refs.size)
    }

    /** The list's usable references, in list order. */
    suspend fun fetchRefs(listUrl: String): List<MediaRef> =
        withContext(Dispatchers.IO) { mdbList.get().fetchList(listUrl) }.toRefs()

    /**
     * Grows [seed] (the row already loaded for [source], covering its first [seedPages] TMDB pages) to
     * up to [cap] titles without re-fetching what it holds: TMDB lists fetch only the pages after
     * [seedPages], all at once; MDBLists resume hydrating after the seed's last title. New titles are
     * de-duplicated against the seed and age-filtered for [restriction]; the seed stays the prefix.
     */
    suspend fun expand(source: CatalogSource, restriction: AgeRestriction?, cap: Int, seed: CatalogRow, seedPages: Int): CatalogRow {
        if (!seed.hasMore || seed.items.size >= cap) return CatalogRow(seed.items.take(cap), seed.hasMore || seed.items.size > cap)
        return when (source) {
            is CatalogSource.MdbList -> expandMdbList(source, restriction, cap, seed)
            is CatalogSource.Discover, is CatalogSource.Trending, is CatalogSource.FamilyTrending ->
                expandTmdbList(source, restriction, cap, seed, seedPages)
            is CatalogSource.Addon -> addonCatalogs.expand(source, cap, seed) { allowed(it, restriction) }
        }
    }

    /**
     * Fetches pages `1..ceil(cap / 20)` concurrently, de-duplicates across pages and age-filters. A row
     * (`cap` = 20) needs only page 1 and flags `View More` when TMDB reports more pages.
     */
    private suspend fun loadTmdbList(source: CatalogSource, restriction: AgeRestriction?, cap: Int): CatalogRow {
        val lastPage = pagesFor(cap)
        val pages = fetchPages(source, 1..lastPage)
        val totalPages = pages.first().totalPages
        val titles = pages.take(maxOf(totalPages, 1)).flatMap { it.items }.distinctBy { it.key }
        val allowed = admit(source, titles, restriction)
        return CatalogRow(items = allowed.take(cap), hasMore = allowed.size > cap || totalPages > lastPage)
    }

    private suspend fun expandTmdbList(
        source: CatalogSource,
        restriction: AgeRestriction?,
        cap: Int,
        seed: CatalogRow,
        seedPages: Int,
    ): CatalogRow {
        val lastPage = pagesFor(cap)
        if (seedPages >= lastPage) return seed
        val pages = fetchPages(source, seedPages + 1..lastPage)
        val seen = seed.items.mapTo(HashSet()) { it.key }
        val fresh = pages.flatMap { it.items }.distinctBy { it.key }.filter { it.key !in seen }
        val items = seed.items + admit(source, fresh, restriction)
        return CatalogRow(items = items.take(cap), hasMore = items.size > cap || pages.maxOf { it.totalPages } > lastPage)
    }

    private suspend fun expandMdbList(source: CatalogSource.MdbList, restriction: AgeRestriction?, cap: Int, seed: CatalogRow): CatalogRow {
        val refs = fetchRefs(source.listUrl)
        val seedRefs = seed.items.mapTo(HashSet()) { it.ref }
        val resumeAt = refs.indexOfLast { it in seedRefs } + 1
        val rest = collectAllowed(refs.drop(resumeAt).filter { it !in seedRefs }, restriction, cap - seed.items.size)
        return CatalogRow(items = seed.items + rest.items, hasMore = rest.hasMore)
    }

    /** [pages] of [source], fetched concurrently and returned in page order. */
    private suspend fun fetchPages(source: CatalogSource, pages: IntRange): List<TitlePage> {
        val names = genres.names(source.mediaType())
        return coroutineScope { pages.map { page -> async { fetchPage(source, page, names) } }.awaitAll() }
    }

    private suspend fun fetchPage(source: CatalogSource, page: Int, names: Map<Int, String>): TitlePage =
        hydrator.withTmdb { tmdb -> tmdb.fetchPage(source, page, names) }

    private suspend fun TmdbApiService.fetchPage(source: CatalogSource, page: Int, names: Map<Int, String>): TitlePage =
        when (source) {
            is CatalogSource.Discover -> when (source.mediaType) {
                MediaType.MOVIE -> discoverMovies(source.provider, page, source.genreId).let { result ->
                    TitlePage(result.results.map { it.toMediaItem(imageUrls, names) }, result.totalPages)
                }
                MediaType.TV -> discoverTv(source.provider, page, source.genreId).let { result ->
                    TitlePage(result.results.map { it.toMediaItem(imageUrls, names) }, result.totalPages)
                }
            }
            is CatalogSource.Trending -> when (source.mediaType) {
                MediaType.MOVIE -> trendingMovies(page).let { result ->
                    TitlePage(result.results.map { it.toMediaItem(imageUrls, names) }, result.totalPages)
                }
                MediaType.TV -> trendingTv(page).let { result ->
                    TitlePage(result.results.map { it.toMediaItem(imageUrls, names) }, result.totalPages)
                }
            }
            is CatalogSource.FamilyTrending -> fetchFamilyPage(source.mediaType, page, names)
            is CatalogSource.MdbList, is CatalogSource.Addon -> error("Only TMDB list sources are paged through TMDB")
        }

    /**
     * One page of a Kids family row. Titles carrying a blocked genre (in any position) or none of the
     * allowed genres are dropped here, on TMDB's own `genre_ids`, before they are ever hydrated.
     */
    private suspend fun TmdbApiService.fetchFamilyPage(mediaType: MediaType, page: Int, names: Map<Int, String>): TitlePage {
        val rule = FamilyCatalogPolicy.rule(mediaType)
        val today = LocalDate.now(clock)
        return when (mediaType) {
            MediaType.MOVIE -> discoverFamilyMovies(page, rule, FamilyCatalogPolicy.movieWindow(today)).let { result ->
                val kept = result.results.filter { rule.acceptsGenres(it.genreIds) }
                TitlePage(kept.map { it.toMediaItem(imageUrls, names) }, result.totalPages)
            }
            MediaType.TV -> discoverFamilyTv(page, rule, FamilyCatalogPolicy.showWindow(today)).let { result ->
                val kept = result.results.filter { rule.acceptsGenres(it.genreIds) }
                TitlePage(kept.map { it.toMediaItem(imageUrls, names) }, result.totalPages)
            }
        }
    }

    /**
     * The [titles] of a TMDB list [source] the profile may see. Family rows always hydrate (so their
     * certification allowlist holds even without a [restriction]) and must pass both that allowlist
     * and the profile's ceiling; unrated titles are never kept there.
     */
    private suspend fun admit(source: CatalogSource, titles: List<MediaItem>, restriction: AgeRestriction?): List<MediaItem> {
        if (source !is CatalogSource.FamilyTrending) return allowed(titles, restriction)
        val rule = FamilyCatalogPolicy.rule(source.mediaType)
        return hydrator.hydrateAll(titles.map { it.ref })
            .filter { rule.acceptsCertification(it.certification) && isAllowed(it, restriction) }
    }

    private fun isAllowed(item: MediaItem, restriction: AgeRestriction?): Boolean =
        ageEngine.isAllowed(item.certification, restriction, item.isAdultContent)

    private data class TitlePage(val items: List<MediaItem>, val totalPages: Int)

    companion object {
        /** How far down an MDBList [collectAllowed] may scan to back-fill filtered titles. */
        const val MAX_SCAN_FACTOR = 4

        /** TMDB pages needed to collect [cap] titles. */
        fun pagesFor(cap: Int): Int = (cap + TmdbApiService.PAGE_SIZE - 1) / TmdbApiService.PAGE_SIZE

        private fun CatalogSource.mediaType(): MediaType = when (this) {
            is CatalogSource.Discover -> mediaType
            is CatalogSource.Trending -> mediaType
            is CatalogSource.FamilyTrending -> mediaType
            is CatalogSource.Addon -> mediaType
            is CatalogSource.MdbList -> MediaType.MOVIE
        }
    }
}

/** Usable references in list order: entries without a TMDB id or a known media type are dropped. */
internal fun List<MdbListItem>.toRefs(): List<MediaRef> = mapNotNull { item ->
    val id = item.tmdbId ?: return@mapNotNull null
    val type = item.mediaType ?: return@mapNotNull null
    MediaRef(id, type)
}.distinct()
