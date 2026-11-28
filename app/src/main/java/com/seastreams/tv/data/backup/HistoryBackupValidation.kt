package com.seastreams.tv.data.backup

import com.seastreams.tv.core.util.enumByNameOrNull
import com.seastreams.tv.domain.model.MediaType

/**
 * Checks a profile's backed-up Library, Watch Progress and removed Next Up cards before anything is written (`AppBackupCodec.decode`). Every
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
        checkDismissals(label, entry.nextUpDismissals)
    }

    private fun checkDismissals(label: String, dismissals: List<NextUpDismissalBackup>) {
        if (dismissals.size > MAX_LIBRARY_ITEMS) fail("$label: more than $MAX_LIBRARY_ITEMS removed Next Up cards")
        dismissals.forEachIndexed { i, d ->
            val where = "$label: removed Next Up card ${i + 1}"
            if (d.tmdbId <= 0) fail("$where has an invalid TMDB id")
            if (d.seasonNumber <= 0 || d.episodeNumber <= 0) fail("$where has an invalid season or episode")
            val aired = listOf(d.lastAiredSeason, d.lastAiredEpisode)
            if (aired.count { it == null } == 1 || aired.any { it != null && it <= 0 }) fail("$where has an invalid latest episode")
            if (d.dismissedAtMillis < 0) fail("$where has an invalid date")
        }
        if (dismissals.map { it.tmdbId }.toSet().size != dismissals.size) fail("$label removes the same Next Up show twice")
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
