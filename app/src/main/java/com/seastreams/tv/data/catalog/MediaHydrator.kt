package com.seastreams.tv.data.catalog

import com.seastreams.tv.core.network.TmdbApiKeyMissingException
import com.seastreams.tv.core.network.TmdbApiService
import com.seastreams.tv.core.network.TmdbMovieDetails
import com.seastreams.tv.core.network.TmdbTvDetails
import com.seastreams.tv.domain.model.MediaItem
import com.seastreams.tv.domain.model.MediaRef
import com.seastreams.tv.domain.model.MediaType
import dagger.Lazy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Turns bare TMDB ids (as exported by MDBList) into display-ready [MediaItem]s using the TMDB
 * details endpoint, which also carries the US certification the age filter needs.
 *
 * Details responses are HTTP-cached for 12h. A single app-wide semaphore bounds concurrent TMDB
 * lookups so several rows loading at once stay well inside TMDB's rate limit. `/external_ids` is
 * never called here (CLAUDE.md rule 5).
 */
@Singleton
class MediaHydrator @Inject constructor(
    /** Lazy so the HTTP stack is built on IO, never during ViewModel creation on the main thread. */
    private val tmdb: Lazy<TmdbApiService>,
    private val imageUrls: TmdbImageUrls,
) {
    private val gate = Semaphore(MAX_CONCURRENT_LOOKUPS)

    /**
     * @return the hydrated title, or `null` when TMDB has no such title or the lookup failed.
     * @throws TmdbApiKeyMissingException when no TMDB key is configured (every lookup would fail).
     */
    suspend fun hydrate(ref: MediaRef): MediaItem? = try {
        withTmdb { service ->
            when (ref.mediaType) {
                MediaType.MOVIE -> service.getMovieDetails(ref.tmdbId).toMediaItem(imageUrls)
                MediaType.TV -> service.getTvDetails(ref.tmdbId).toMediaItem(imageUrls)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: TmdbApiKeyMissingException) {
        throw e
    } catch (e: Exception) {
        // One deleted/broken title must not take down the whole row.
        null
    }

    /**
     * Runs a raw TMDB lookup on IO under the app-wide concurrency gate. Every per-title TMDB call
     * (rows, Continue Watching, Library imports) goes through here so they share one rate budget.
     */
    suspend fun <T> withTmdb(block: suspend (TmdbApiService) -> T): T = withContext(Dispatchers.IO) {
        gate.withPermit { block(tmdb.get()) }
    }

    /** Hydrates [refs] concurrently, preserving their order and dropping failed lookups. */
    suspend fun hydrateAll(refs: List<MediaRef>): List<MediaItem> = coroutineScope {
        refs.map { ref -> async { hydrate(ref) } }.awaitAll().filterNotNull()
    }

    /**
     * Enriches list results ([items] from `/discover`, `/trending`, `/find`) with what only the details
     * endpoint carries: the stylized title logo (English, else language-neutral), runtime and US
     * certification. Lookups run concurrently under the shared gate and are HTTP-cached for 12h; a
     * title whose lookup fails keeps its list version (falling back to the slot's styled title text)
     * rather than being dropped. Never calls `/external_ids` (CLAUDE.md rule 5).
     * @throws TmdbApiKeyMissingException when no TMDB key is configured.
     */
    suspend fun hydrateOrKeep(items: List<MediaItem>): List<MediaItem> = coroutineScope {
        items.map { item -> async { hydrate(item.ref) ?: item } }.awaitAll()
    }

    companion object {
        const val MAX_CONCURRENT_LOOKUPS = 6
    }
}

internal fun TmdbMovieDetails.toMediaItem(images: TmdbImageUrls) = MediaItem(
    tmdbId = id,
    mediaType = MediaType.MOVIE,
    title = title,
    overview = overview,
    posterUrl = images.poster(posterPath),
    backdropUrl = images.backdrop(backdropPath),
    heroBackdropUrl = images.heroBackdrop(backdropPath),
    logoUrl = images.logo(this.images?.preferredLogoPath()),
    year = yearOf(releaseDate),
    ratingPercent = ratingPercent(voteAverage),
    genres = genres.map { it.name }.filter { it.isNotBlank() },
    runtimeMinutes = runtime?.takeIf { it > 0 },
    certification = usCertification(),
    isAdultContent = adult,
)

internal fun TmdbTvDetails.toMediaItem(images: TmdbImageUrls) = MediaItem(
    tmdbId = id,
    mediaType = MediaType.TV,
    title = name,
    overview = overview,
    posterUrl = images.poster(posterPath),
    backdropUrl = images.backdrop(backdropPath),
    heroBackdropUrl = images.heroBackdrop(backdropPath),
    logoUrl = images.logo(this.images?.preferredLogoPath()),
    year = yearOf(firstAirDate),
    ratingPercent = ratingPercent(voteAverage),
    genres = genres.map { it.name }.filter { it.isNotBlank() },
    runtimeMinutes = episodeRunTime.firstOrNull { it > 0 },
    certification = usCertification(),
    isAdultContent = adult,
)

/** `"2026-03-14"` → `2026`. */
internal fun yearOf(date: String?): Int? = date?.take(YEAR_LENGTH)?.toIntOrNull()

/** TMDB's 0–10 vote average as a whole percentage; `null` when unrated. */
internal fun ratingPercent(voteAverage: Double): Int? =
    if (voteAverage > 0.0) (voteAverage * PERCENT_PER_POINT).roundToInt().coerceIn(0, MAX_PERCENT) else null

private const val YEAR_LENGTH = 4
private const val PERCENT_PER_POINT = 10
private const val MAX_PERCENT = 100
