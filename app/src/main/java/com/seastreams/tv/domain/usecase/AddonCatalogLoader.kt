package com.seastreams.tv.domain.usecase

import com.seastreams.tv.core.network.StremioAddonClient
import com.seastreams.tv.core.network.StremioMetaPreview
import com.seastreams.tv.core.network.TmdbApiKeyMissingException
import com.seastreams.tv.data.catalog.GenreRepository
import com.seastreams.tv.data.catalog.MediaHydrator
import com.seastreams.tv.data.catalog.TmdbImageUrls
import com.seastreams.tv.data.catalog.toMediaItem
import com.seastreams.tv.domain.model.CatalogRow
import com.seastreams.tv.domain.model.CatalogSource
import com.seastreams.tv.domain.model.MediaItem
import com.seastreams.tv.domain.model.MediaRef
import com.seastreams.tv.domain.model.MediaType
import dagger.Lazy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads the titles of a Stremio addon catalog ([CatalogSource.Addon]) as display-ready [MediaItem]s.
 *
 * Addon metas are mapped onto TMDB so cards, Details and the age filter work exactly like every
 * other row: `tmdb:{id}` entries are hydrated through TMDB details, IMDb `tt…` entries through one
 * TMDB `/find` call each (never `/external_ids`, CLAUDE.md rule 5). Both go through
 * [MediaHydrator]'s app-wide rate gate and are HTTP-cached for 12h. Other id schemes are dropped.
 *
 * Titles are resolved in batches only as far as needed to fill the cap, and every batch passes
 * through the caller's age filter before it is kept (CLAUDE.md rule 6).
 */
@Singleton
class AddonCatalogLoader @Inject constructor(
    /** Lazy so the HTTP stack is built on IO, never during ViewModel creation on the main thread. */
    private val addonClient: Lazy<StremioAddonClient>,
    private val hydrator: MediaHydrator,
    private val imageUrls: TmdbImageUrls,
    private val genres: GenreRepository,
) {
    /**
     * Up to [cap] titles of [source] that [allowed] keeps, in catalog order, skipping titles whose
     * [MediaItem.key] is in [exclude]. Pages are fetched with Stremio's `skip` extra (at most
     * [MAX_PAGES]); a first-page failure throws, a later one just ends the list.
     */
    suspend fun load(
        source: CatalogSource.Addon,
        cap: Int,
        exclude: Set<String> = emptySet(),
        allowed: suspend (List<MediaItem>) -> List<MediaItem>,
    ): CatalogRow {
        val names = genres.names(source.mediaType)
        val accepted = ArrayList<MediaItem>(cap)
        val seenKeys = HashSet(exclude)
        val seenIds = HashSet<String>()
        val pending = ArrayDeque<StremioMetaPreview>()
        val scanLimit = (cap + exclude.size) * CatalogLoader.MAX_SCAN_FACTOR
        var scanned = 0
        var pages = 0
        var skip = 0
        var exhausted = false
        while (accepted.size < cap && scanned < scanLimit) {
            if (pending.isEmpty()) {
                if (pages >= MAX_PAGES) break
                val page = fetchPage(source, skip, isFirst = pages == 0)
                pages++
                skip += page.size
                val fresh = page.filter { seenIds.add(it.id) }
                if (fresh.isEmpty()) {
                    exhausted = true
                    break
                }
                pending.addAll(fresh)
            }
            val batch = List(minOf(cap - accepted.size, pending.size, scanLimit - scanned)) { pending.removeFirst() }
            scanned += batch.size
            val items = resolveAll(batch, source.mediaType, names).filter { seenKeys.add(it.key) }
            accepted += allowed(items)
        }
        val hasMore = accepted.size > cap || pending.isNotEmpty() || (!exhausted && pages < MAX_PAGES)
        return CatalogRow(items = accepted.take(cap), hasMore = hasMore)
    }

    /** Grows [seed] (an already-loaded row of [source]) to up to [cap] titles; the seed stays the prefix. */
    suspend fun expand(
        source: CatalogSource.Addon,
        cap: Int,
        seed: CatalogRow,
        allowed: suspend (List<MediaItem>) -> List<MediaItem>,
    ): CatalogRow {
        val rest = load(source, cap - seed.items.size, seed.items.mapTo(HashSet()) { it.key }, allowed)
        return CatalogRow(items = seed.items + rest.items, hasMore = rest.hasMore)
    }

    private suspend fun fetchPage(source: CatalogSource.Addon, skip: Int, isFirst: Boolean): List<StremioMetaPreview> = try {
        withContext(Dispatchers.IO) {
            addonClient.get().fetchCatalog(source.manifestUrl, source.mediaType.stremioType, source.catalogId, skip)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (isFirst) throw e
        emptyList()
    }

    private suspend fun resolveAll(previews: List<StremioMetaPreview>, mediaType: MediaType, names: Map<Int, String>): List<MediaItem> =
        coroutineScope { previews.map { async { resolve(it, mediaType, names) } }.awaitAll().filterNotNull() }

    private suspend fun resolve(preview: StremioMetaPreview, mediaType: MediaType, names: Map<Int, String>): MediaItem? {
        if (preview.type.isNotEmpty() && preview.type != mediaType.stremioType) return null
        val id = preview.id
        return when {
            id.startsWith(TMDB_PREFIX) ->
                id.removePrefix(TMDB_PREFIX).toIntOrNull()?.let { hydrator.hydrate(MediaRef(it, mediaType)) }
            IMDB_ID.matches(id) -> findByImdbId(id, mediaType, names)
            else -> null
        }
    }

    private suspend fun findByImdbId(imdbId: String, mediaType: MediaType, names: Map<Int, String>): MediaItem? = try {
        val found = hydrator.withTmdb { tmdb -> tmdb.findByImdbId(imdbId) }
        val summary = when (mediaType) {
            MediaType.MOVIE -> found.movieResults.firstOrNull()?.toMediaItem(imageUrls, names)
            MediaType.TV -> found.tvResults.firstOrNull()?.toMediaItem(imageUrls, names)
        }
        // `/find` results carry no logo or runtime: hydrate like `tmdb:` ids, keeping the summary on failure.
        summary?.let { hydrator.hydrate(it.ref) ?: it }
    } catch (e: CancellationException) {
        throw e
    } catch (e: TmdbApiKeyMissingException) {
        throw e
    } catch (e: Exception) {
        // One unknown/broken entry must not take down the whole row.
        null
    }

    companion object {
        /** Catalog pages (Stremio `skip` steps) fetched at most per load, bounding View More's work. */
        const val MAX_PAGES = 5

        private const val TMDB_PREFIX = "tmdb:"
        private val IMDB_ID = Regex("tt\\d+")
    }
}
