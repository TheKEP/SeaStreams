package com.seastreams.tv.data.library

import com.seastreams.tv.core.database.dao.LibraryDao
import com.seastreams.tv.core.network.TmdbApiKeyMissingException
import com.seastreams.tv.core.util.ApplicationScope
import com.seastreams.tv.data.catalog.MediaHydrator
import com.seastreams.tv.domain.filter.LibraryKeywordGenres
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Looks up TMDB keywords for Library movies saved before they were stored (`keywordIds IS NULL`: rows from before
 * Room v11 or an older backup), so keyword genres such as `Christmas` match them too (PRD §7).
 *
 * Started at launch for the active profile and on every profile switch ([followActiveProfile], from `SeaStreamsApp`),
 * with `LibraryViewModel.open` as a fallback; [ensure] makes every trigger idempotent (one complete run per profile per
 * process). TMDB-friendly (CLAUDE.md rule 5): [CHUNK_SIZE] titles at a time, [WORKERS] concurrent
 * `/movie/{id}/keywords` requests, each through [MediaHydrator.withTmdb] (the shared TMDB gate, OkHttp's disk cache and
 * 429 retry), never `/external_ids`. Each chunk is written in one transaction, so the Library re-derives once per
 * chunk, not per title. A title that fails stays `NULL` for a later launch; a missing API key ends the run quietly
 * (what the chunk already fetched is kept) without marking the profile done.
 */
@Singleton
class LibraryKeywordBackfill @Inject constructor(
    private val libraryDao: LibraryDao,
    private val hydrator: MediaHydrator,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val lock = Mutex()
    private val started = HashSet<Long>()
    private val workers = Semaphore(WORKERS)

    /** Calls [ensure] for every active profile [activeProfileId] emits; suspends while the flow is collected. */
    suspend fun followActiveProfile(activeProfileId: Flow<Long?>) {
        activeProfileId.filterNotNull().collect { ensure(it) }
    }

    /** Starts [profileId]'s backfill unless it already ran (or is running) in this process. */
    fun ensure(profileId: Long) {
        scope.launch {
            if (!lock.withLock { started.add(profileId) }) return@launch
            val completed = run(profileId)
            // An interrupted run (no API key, cancelled) may try again on the next trigger.
            if (!completed) lock.withLock { started.remove(profileId) }
        }
    }

    /** `true` when every missing movie was attempted. */
    internal suspend fun run(profileId: Long): Boolean {
        for (chunk in libraryDao.moviesMissingKeywords(profileId).chunked(CHUNK_SIZE)) {
            val results = coroutineScope {
                chunk.map { tmdbId -> async { tmdbId to workers.withPermit { fetch(tmdbId) } } }.awaitAll()
            }
            val found = results.mapNotNull { (tmdbId, result) -> (result as? Lookup.Found)?.let { tmdbId to it.keywordIds } }
            if (found.isNotEmpty()) libraryDao.fillMissingMovieKeywordIds(profileId, found.toMap())
            if (results.any { it.second == Lookup.KeyMissing }) return false
        }
        return true
    }

    private suspend fun fetch(tmdbId: Int): Lookup = try {
        val ids = hydrator.withTmdb { it.getMovieKeywords(tmdbId) }.keywords.map { it.id }
        Lookup.Found(LibraryKeywordGenres.storedKeywordIds(ids))
    } catch (e: CancellationException) {
        throw e
    } catch (e: TmdbApiKeyMissingException) {
        Lookup.KeyMissing
    } catch (e: Exception) {
        Lookup.Failed // Deleted on TMDB or a transient error: stays NULL for a later launch.
    }

    private sealed interface Lookup {
        data class Found(val keywordIds: String) : Lookup
        data object Failed : Lookup
        data object KeyMissing : Lookup
    }

    companion object {
        const val CHUNK_SIZE = 25

        /** Concurrent keyword requests; below `MediaHydrator`'s app-wide gate, so other TMDB work still gets permits. */
        const val WORKERS = 4
    }
}
