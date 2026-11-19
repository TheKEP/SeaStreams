package com.seastreams.tv.domain.playback

import com.seastreams.tv.core.database.dao.LastUsedStreamDao
import com.seastreams.tv.core.database.entity.LastUsedStreamEntity
import com.seastreams.tv.domain.model.StreamSource
import java.time.Clock
import javax.inject.Inject

/** The identity of the stream that last started for one movie or episode (PRD §6 `LAST USED`). */
data class LastUsedStream(val addonUrl: String, val url: String, val label: String, val description: String)

/**
 * Where [saved] sits in a freshly fetched [streams] list, or `null` when it isn't there. Addon
 * streams carry no stable id, so the match is tiered: the exact URL first; then the same addon,
 * label and description (debrid/proxy addons sign a new URL on every fetch); then the same addon and
 * label, but only when exactly one stream in the list has that pair.
 */
fun lastUsedIndex(streams: List<StreamSource>, saved: LastUsedStream?): Int? {
    if (saved == null) return null
    streams.indexOfFirst { it.url == saved.url }.takeIf { it >= 0 }?.let { return it }
    streams.indexOfFirst { it.addonUrl == saved.addonUrl && it.label == saved.label && it.description == saved.description }
        .takeIf { it >= 0 }?.let { return it }
    val sameLabel = streams.withIndex().filter { (_, s) -> s.addonUrl == saved.addonUrl && s.label == saved.label }
    return sameLabel.singleOrNull()?.index
}

/**
 * Reads and writes the per-profile `LAST USED` stream of each movie (season/episode `0`) or episode
 * ([LastUsedStreamDao]). Recorded when a stream actually starts: the built-in player's first `Ready`
 * for that stream, or the hand-off to an external player. Trailers are never recorded.
 */
class LastUsedStreamRecorder @Inject constructor(
    private val dao: LastUsedStreamDao,
    private val clock: Clock,
) {
    suspend fun record(target: PlaybackTarget, stream: StreamSource) {
        val media = target.details.media
        dao.upsert(
            LastUsedStreamEntity(
                profileId = target.profileId,
                tmdbId = media.tmdbId,
                mediaType = media.mediaType,
                seasonNumber = target.episode?.season ?: 0,
                episodeNumber = target.episode?.episode ?: 0,
                addonUrl = stream.addonUrl,
                streamUrl = stream.url,
                label = stream.label,
                description = stream.description,
                updatedAtMillis = clock.millis(),
            ),
        )
    }

    suspend fun lastUsed(target: PlaybackTarget): LastUsedStream? {
        val media = target.details.media
        val row = dao.get(target.profileId, media.tmdbId, media.mediaType, target.episode?.season ?: 0, target.episode?.episode ?: 0)
            ?: return null
        return LastUsedStream(row.addonUrl, row.streamUrl, row.label, row.description)
    }

    /** The index of [target]'s `LAST USED` stream in [streams], or `null` when there is none. */
    suspend fun indexIn(target: PlaybackTarget, streams: List<StreamSource>): Int? =
        if (streams.isEmpty()) null else lastUsedIndex(streams, lastUsed(target))
}
