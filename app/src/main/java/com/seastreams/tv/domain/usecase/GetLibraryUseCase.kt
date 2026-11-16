package com.seastreams.tv.domain.usecase

import com.seastreams.tv.core.database.dao.LibraryDao
import com.seastreams.tv.core.database.dao.LibraryItemWithActivity
import com.seastreams.tv.core.database.dao.ProfileDao
import com.seastreams.tv.core.database.entity.LibrarySortOrder
import com.seastreams.tv.core.database.entity.ProfileEntity
import com.seastreams.tv.data.catalog.TmdbImageUrls
import com.seastreams.tv.domain.filter.AgeRestrictionEngine
import com.seastreams.tv.domain.model.MediaItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** One saved title with what the Library's filters and sort orders need. */
data class LibraryEntry(
    val media: MediaItem,
    val genreIds: List<Int>,
    val addedAtMillis: Long,
    /** Most recent playback of the title (any episode); `null` when never watched. */
    val lastWatchedAtMillis: Long?,
)

/**
 * A profile's Library (PRD §7). Kids profiles are re-checked against their *current* ceiling, so
 * lowering it hides saved titles that are now out of range (CLAUDE.md rule 6).
 */
class GetLibraryUseCase @Inject constructor(
    private val libraryDao: LibraryDao,
    private val profileDao: ProfileDao,
    private val imageUrls: TmdbImageUrls,
    private val ageEngine: AgeRestrictionEngine,
) {
    fun observe(profile: ProfileEntity): Flow<List<LibraryEntry>> {
        val restriction = profile.toAgeRestriction()
        return libraryDao.observeWithActivity(profile.id).map { rows ->
            rows.filter { ageEngine.isAllowed(it.item.certification, restriction) }.map { it.toEntry() }
        }
    }

    /** Persists the profile's chosen sort order; the next Library visit opens with it. */
    suspend fun setSortOrder(profileId: Long, sortOrder: LibrarySortOrder) =
        profileDao.updateLibrarySortOrder(profileId, sortOrder)

    private fun LibraryItemWithActivity.toEntry() = LibraryEntry(
        media = MediaItem(
            tmdbId = item.tmdbId,
            mediaType = item.mediaType,
            title = item.title,
            overview = "",
            posterUrl = imageUrls.poster(item.posterPath),
            backdropUrl = null,
            heroBackdropUrl = null,
            logoUrl = null,
            year = null,
            ratingPercent = null,
            genres = emptyList(),
            runtimeMinutes = null,
            certification = item.certification,
            isAdultContent = false,
        ),
        genreIds = item.genreIds.split(',').mapNotNull { it.trim().toIntOrNull() },
        addedAtMillis = item.addedAtMillis,
        lastWatchedAtMillis = lastWatchedAtMillis,
    )
}
