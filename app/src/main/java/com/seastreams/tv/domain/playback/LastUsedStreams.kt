package com.seastreams.tv.domain.playback

import com.seastreams.tv.core.database.dao.LastUsedStreamDao
import com.seastreams.tv.core.database.entity.LastUsedStreamEntity
import com.seastreams.tv.core.util.sha256Hex
import com.seastreams.tv.domain.model.StreamSource
import java.time.Clock
import javax.inject.Inject

/**
 * The identity of the stream that last started for one movie or episode (PRD §6 `LAST USED`). [urlFingerprint] is
 * [streamUrlFingerprint] of its URL (`""` when unknown): the URL itself is never stored, since it may carry keys.
 */
data class LastUsedStream(val addonUrl: String, val urlFingerprint: String, val label: String, val description: String)

/** What `last_used_streams` keeps of a stream URL: its SHA-256, so API keys and signed tokens never reach the database. */
fun streamUrlFingerprint(url: String): String = sha256Hex(url)

/**
 * Where [saved] sits in a freshly fetched [streams] list, or `null` when it isn't there. Addon
 * streams carry no stable id, so the match is tiered, strictest first, within the same addon:
 * 1. the exact URL (by fingerprint);
 * 2. the same label and description (debrid/proxy addons sign a new URL on every fetch);
 * 3. the same label and description once every number is ignored (torrent addons put live seeder
 *    / peer counts in the description) — only when exactly one stream qualifies;
 * 4. the same label and first description line (the release / file name) — only when unique;
 * 5. the same label — only when unique.
 * The looser tiers never guess between two candidates.
 */
fun lastUsedIndex(streams: List<StreamSource>, saved: LastUsedStream?): Int? {
    if (saved == null) return null
    if (saved.urlFingerprint.isNotEmpty()) {
        streams.indexOfFirst { streamUrlFingerprint(it.url) == saved.urlFingerprint }.takeIf { it >= 0 }?.let { return it }
    }
    val sameAddon = streams.withIndex().filter { (_, s) -> s.addonUrl == saved.addonUrl && s.label == saved.label }
    sameAddon.firstOrNull { (_, s) -> s.description == saved.description }?.let { return it.index }
    val digitless = digitless(saved.description)
    sameAddon.singleOrNull { (_, s) -> digitless(s.description) == digitless }?.let { return it.index }
    val firstLine = firstLine(saved.description)
    if (firstLine.isNotEmpty()) {
        sameAddon.singleOrNull { (_, s) -> firstLine(s.description) == firstLine }?.let { return it.index }
    }
    return sameAddon.singleOrNull()?.index
}

private val Digits = Regex("[0-9]+")

private fun digitless(description: String): String = description.replace(Digits, "#")

private fun firstLine(description: String): String = description.lineSequence().map(String::trim).firstOrNull(String::isNotEmpty).orEmpty()

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
                streamUrlFingerprint = streamUrlFingerprint(stream.url),
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
        return LastUsedStream(row.addonUrl, row.streamUrlFingerprint, row.label, row.description)
    }

    /** The index of [target]'s `LAST USED` stream in [streams], or `null` when there is none. */
    suspend fun indexIn(target: PlaybackTarget, streams: List<StreamSource>): Int? =
        if (streams.isEmpty()) null else lastUsedIndex(streams, lastUsed(target))

    /**
     * The stream auto-play starts for [resolved] (Play/Resume with *Auto-play top stream*, the episode
     * menu's `Auto-play`, Continue Watching, Next Episode, auto-next): its `LAST USED` stream when the
     * fresh list holds it, else the top stream (preferred auto-play addon first).
     */
    suspend fun autoPlayIndex(resolved: ResolvedPlayback): Int =
        indexIn(resolved.target, resolved.resolution.streams) ?: resolved.topIndex
}
