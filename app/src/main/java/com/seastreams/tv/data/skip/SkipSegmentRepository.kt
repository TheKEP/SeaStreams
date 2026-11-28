package com.seastreams.tv.data.skip

import com.seastreams.tv.domain.model.EpisodeRef
import com.seastreams.tv.domain.model.StreamSource
import com.seastreams.tv.domain.playback.skip.MediaChapter
import com.seastreams.tv.domain.playback.skip.SkipSegment
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Skip-time sources for the built-in player (PRD §9 *Skip & Next Episode Prompts*): IntroDB per title (answers,
 * including "nothing timed yet", are kept for the app session in a small LRU so replays and stream switches don't
 * ask again; failures aren't kept, so the next play retries) and the stream's Matroska chapters.
 */
@Singleton
class SkipSegmentRepository @Inject constructor(
    private val introDb: IntroDbClient,
    private val chapterReader: MatroskaChapterReader,
) {
    private val cache = object : LinkedHashMap<String, List<SkipSegment>>(CACHE_SIZE, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<SkipSegment>>?): Boolean = size > CACHE_SIZE
    }

    /** IntroDB's segments for the movie ([episode] `null`) or episode; empty when unknown or unreachable. */
    suspend fun online(imdbId: String?, episode: EpisodeRef?): List<SkipSegment> {
        val id = imdbId ?: return emptyList()
        val key = episode?.let { "$id:${it.season}:${it.episode}" } ?: id
        synchronized(cache) { cache[key] }?.let { return it }
        val fetched = introDb.segments(id, episode) ?: return emptyList()
        synchronized(cache) { cache[key] = fetched }
        return fetched
    }

    /** The stream's own chapters (Matroska/WebM only; the caller checks the container). */
    suspend fun chapters(stream: StreamSource): List<MediaChapter> = chapterReader.read(stream)

    private companion object {
        const val CACHE_SIZE = 32
        const val LOAD_FACTOR = 0.75f
    }
}
