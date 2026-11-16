package com.seastreams.tv.data.catalog

import com.seastreams.tv.core.util.orNullUnlessCancelled
import com.seastreams.tv.domain.model.Genre
import com.seastreams.tv.domain.model.MediaType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TMDB's movie and TV genre lists. They practically never change, so each list is fetched once per
 * process (the HTTP cache keeps it for 6h across restarts). A failed fetch is not remembered.
 */
@Singleton
class GenreRepository @Inject constructor(private val hydrator: MediaHydrator) {

    private val lock = Mutex()
    private val loaded = HashMap<MediaType, List<Genre>>()

    /** @throws Exception when TMDB can't be reached and nothing was loaded before. */
    suspend fun genres(mediaType: MediaType): List<Genre> {
        lock.withLock { loaded[mediaType] }?.let { return it }
        val fetched = hydrator.withTmdb { tmdb -> tmdb.genres(mediaType) }
            .filter { it.name.isNotBlank() }
            .map { Genre(it.id, it.name) }
        lock.withLock { loaded[mediaType] = fetched }
        return fetched
    }

    /** `genre id → name` for [mediaType]; empty when the list can't be loaded (names are cosmetic). */
    suspend fun names(mediaType: MediaType): Map<Int, String> =
        orNullUnlessCancelled { genres(mediaType) }.orEmpty().associate { it.id to it.name }

    /** Movie and TV genre names merged (ids like 18 `Drama` are shared by both lists). */
    suspend fun allNames(): Map<Int, String> = names(MediaType.TV) + names(MediaType.MOVIE)
}
