package com.seastreams.tv.data.backup

import com.seastreams.tv.core.util.enumByNameOrNull
import com.seastreams.tv.domain.model.MediaType

/**
 * Checks a profile's backed-up Library and Watch Progress before anything is written (`AppBackupCodec.decode`). Every
 * failure names the profile ([label]) and the entry, and throws [InvalidAppBackupException].
 */
internal object HistoryBackupValidation {

    const val MAX_LIBRARY_ITEMS = 20_000
    const val MAX_PROGRESS_ROWS = 100_000
    private const val MAX_TITLE_CHARS = 500
    private const val MAX_PATH_CHARS = 512
    private val GENRE_IDS = Regex("^(\\d+(,\\d+)*)?$")

    fun check(label: String, entry: ProfileBackupEntry) {
        if (entry.library.size > MAX_LIBRARY_ITEMS) fail("$label: more than $MAX_LIBRARY_ITEMS Library titles")
        if (entry.watchProgress.size > MAX_PROGRESS_ROWS) fail("$label: more than $MAX_PROGRESS_ROWS watch progress entries")

        entry.library.forEachIndexed { i, item ->
            val where = "$label: Library title ${i + 1}"
            common(where, item.tmdbId, item.mediaType, item.title, item.posterPath)
            if (!GENRE_IDS.matches(item.genreIds)) fail("$where has invalid genre ids")
            if (item.addedAtMillis < 0) fail("$where has an invalid date added")
            if ((item.certification?.length ?: 0) > MAX_PATH_CHARS) fail("$where has an invalid rating")
        }
        if (entry.library.map { it.tmdbId to it.mediaType }.toSet().size != entry.library.size) {
            fail("$label lists a Library title twice")
        }

        entry.watchProgress.forEachIndexed { i, p ->
            val where = "$label: watch progress entry ${i + 1}"
            val type = common(where, p.tmdbId, p.mediaType, p.title, p.posterPath)
            if (p.seasonNumber < 0 || p.episodeNumber < 0) fail("$where has a negative season or episode")
            if (type == MediaType.MOVIE && (p.seasonNumber != 0 || p.episodeNumber != 0)) fail("$where is a movie with a season or episode")
            if (p.positionMs < 0 || p.durationMs < 0) fail("$where has a negative position or duration")
            if (p.updatedAtMillis < 0) fail("$where has an invalid date")
            if ((p.episodeTitle?.length ?: 0) > MAX_TITLE_CHARS || (p.backdropPath?.length ?: 0) > MAX_PATH_CHARS) fail("$where is too long")
        }
        val keys = entry.watchProgress.map { listOf(it.tmdbId, it.mediaType, it.seasonNumber, it.episodeNumber) }
        if (keys.toSet().size != keys.size) fail("$label lists the same watch progress entry twice")
    }

    /** The parsed media type, once id, type, title and poster path are valid. */
    fun mediaType(raw: String): MediaType = enumByNameOrNull<MediaType>(raw) ?: fail("Unknown media type “${raw.take(20)}”")

    private fun common(where: String, tmdbId: Int, mediaType: String, title: String, posterPath: String?): MediaType {
        if (tmdbId <= 0) fail("$where has an invalid TMDB id")
        val type = enumByNameOrNull<MediaType>(mediaType) ?: fail("$where has an unknown media type “${mediaType.take(20)}”")
        if (title.isBlank() || title.length > MAX_TITLE_CHARS) fail("$where has no valid title")
        if ((posterPath?.length ?: 0) > MAX_PATH_CHARS) fail("$where has an invalid poster path")
        return type
    }

    private fun fail(message: String): Nothing = throw InvalidAppBackupException(message)
}
