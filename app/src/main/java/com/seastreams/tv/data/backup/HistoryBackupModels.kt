package com.seastreams.tv.data.backup

import com.seastreams.tv.core.database.entity.LibraryItemEntity
import com.seastreams.tv.core.database.entity.WatchProgressEntity
import com.seastreams.tv.domain.model.MediaType
import kotlinx.serialization.Serializable

/**
 * One saved Library title, every `library_items` column except `profileId`. [mediaType] is a `MediaType` name, kept
 * as text so an unknown value gets its own message from `HistoryBackupValidation` rather than failing the whole file.
 */
@Serializable
data class LibraryItemBackup(
    val tmdbId: Int,
    val mediaType: String,
    val title: String,
    val posterPath: String? = null,
    val certification: String? = null,
    /** Comma-separated TMDB genre ids. */
    val genreIds: String = "",
    val addedAtMillis: Long,
) {
    /** Only valid after `HistoryBackupValidation` checked [mediaType]. */
    fun toEntity(profileId: Long, type: MediaType) =
        LibraryItemEntity(profileId, tmdbId, type, title, posterPath, certification, genreIds, addedAtMillis)

    companion object {
        fun of(e: LibraryItemEntity) =
            LibraryItemBackup(e.tmdbId, e.mediaType.name, e.title, e.posterPath, e.certification, e.genreIds, e.addedAtMillis)
    }
}

/**
 * One movie's or episode's playback record, every `watch_progress` column except `profileId`, artwork and titles
 * included so restored Continue Watching cards render straight away.
 */
@Serializable
data class WatchProgressBackup(
    val tmdbId: Int,
    val mediaType: String,
    val seasonNumber: Int = 0,
    val episodeNumber: Int = 0,
    val title: String,
    val episodeTitle: String? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAtMillis: Long,
    val dismissedFromContinueWatching: Boolean = false,
) {
    /** Only valid after `HistoryBackupValidation` checked [mediaType]. */
    fun toEntity(profileId: Long, type: MediaType) = WatchProgressEntity(
        profileId, tmdbId, type, seasonNumber, episodeNumber, title, episodeTitle, posterPath, backdropPath,
        positionMs, durationMs, updatedAtMillis, dismissedFromContinueWatching,
    )

    companion object {
        fun of(e: WatchProgressEntity) = WatchProgressBackup(
            e.tmdbId, e.mediaType.name, e.seasonNumber, e.episodeNumber, e.title, e.episodeTitle, e.posterPath,
            e.backdropPath, e.positionMs, e.durationMs, e.updatedAtMillis, e.dismissedFromContinueWatching,
        )
    }
}

/**
 * Which record a restore keeps for one movie or episode (ARCHITECTURE §13): the backup's when the TV has none; otherwise
 * the more recently updated one; on equal times the further-watched one; and the TV's own when that ties too. So
 * restoring an older backup onto a TV in use never rewinds or un-watches anything.
 */
object WatchProgressMerge {

    /** `true` when [backup] should replace [local]. */
    fun backupWins(local: WatchProgressEntity?, backup: WatchProgressEntity): Boolean = when {
        local == null -> true
        backup.updatedAtMillis != local.updatedAtMillis -> backup.updatedAtMillis > local.updatedAtMillis
        else -> completion(backup) > completion(local)
    }

    private fun completion(row: WatchProgressEntity): Double =
        if (row.durationMs <= 0) 0.0 else row.positionMs.toDouble() / row.durationMs
}
