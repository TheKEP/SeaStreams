package com.seastreams.tv.data.library

import com.seastreams.tv.core.database.dao.LibraryDao
import com.seastreams.tv.core.database.entity.LibraryItemEntity
import com.seastreams.tv.core.util.ApplicationScope
import com.seastreams.tv.domain.filter.LibraryKeywordGenres
import com.seastreams.tv.domain.model.MediaRef
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.TitleDetails
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

enum class LibraryAddResult { ADDED, ALREADY_IN_LIBRARY }

/** A "Clear Library" waiting out its Undo window. */
data class PendingLibraryClear(val profileId: Long, val deadlineMillis: Long)

/**
 * Saved titles per profile. Adding is idempotent: an existence check plus the DAO's
 * `OnConflictStrategy.IGNORE` insert mean a title is never stored twice, however fast or often the
 * user presses `Add to library`.
 *
 * "Clear Library" is deferred: nothing is deleted until [CLEAR_UNDO_WINDOW_MILLIS] pass without Undo.
 */
@Singleton
class LibraryRepository @Inject constructor(
    private val libraryDao: LibraryDao,
    private val clock: Clock,
    @param:ApplicationScope private val appScope: CoroutineScope,
) {
    private val addLock = Mutex()
    private val clearLock = Mutex()
    private var clearJob: Job? = null
    private val _pendingClear = MutableStateFlow<PendingLibraryClear?>(null)

    val pendingClear: StateFlow<PendingLibraryClear?> = _pendingClear.asStateFlow()

    fun observeContains(profileId: Long, ref: MediaRef): Flow<Boolean> =
        libraryDao.observeContains(profileId, ref.tmdbId, ref.mediaType)

    suspend fun add(profileId: Long, details: TitleDetails): LibraryAddResult = addLock.withLock {
        val ref = details.ref
        if (libraryDao.contains(profileId, ref.tmdbId, ref.mediaType)) return LibraryAddResult.ALREADY_IN_LIBRARY
        val rowId = libraryDao.insert(
            LibraryItemEntity(
                profileId = profileId,
                tmdbId = ref.tmdbId,
                mediaType = ref.mediaType,
                title = details.media.title,
                posterPath = details.posterPath,
                certification = details.media.certification,
                genreIds = details.genreIds.joinToString(","),
                addedAtMillis = clock.millis(),
                // Shows are never matched by keyword: "" marks them looked up, so they're never backfilled.
                keywordIds = if (ref.mediaType == MediaType.MOVIE) LibraryKeywordGenres.storedKeywordIds(details.keywordIds) else "",
            ),
        )
        if (rowId == -1L) LibraryAddResult.ALREADY_IN_LIBRARY else LibraryAddResult.ADDED
    }

    suspend fun remove(profileId: Long, ref: MediaRef) = libraryDao.remove(profileId, ref.tmdbId, ref.mediaType)

    /** Clears [profileId]'s library after the Undo window unless [undoClear] runs first. */
    suspend fun scheduleClear(profileId: Long) = clearLock.withLock {
        clearJob?.cancel()
        val pending = PendingLibraryClear(profileId, clock.millis() + CLEAR_UNDO_WINDOW_MILLIS)
        _pendingClear.value = pending
        clearJob = appScope.launch {
            delay(CLEAR_UNDO_WINDOW_MILLIS)
            libraryDao.clear(profileId)
            _pendingClear.compareAndSet(pending, null)
        }
    }

    /** Cancels a pending clear; returns whether there was one to cancel. */
    suspend fun undoClear(): Boolean = clearLock.withLock {
        val hadPending = _pendingClear.value != null
        clearJob?.cancel()
        clearJob = null
        _pendingClear.value = null
        hadPending
    }

    companion object {
        const val CLEAR_UNDO_WINDOW_MILLIS = 5_000L
    }
}
