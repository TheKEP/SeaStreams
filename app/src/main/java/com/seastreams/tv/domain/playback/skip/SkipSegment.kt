package com.seastreams.tv.domain.playback.skip

/** What a timed part of a movie/episode is (PRD §9 *Skip & Next Episode Prompts*). */
enum class SegmentKind { INTRO, RECAP, CREDITS, POST_CREDITS }

/** Where a segment's times came from: the file's own Matroska chapters or IntroDB. */
enum class SegmentSource { CHAPTERS, INTRODB }

/** One skippable part of the playing file, in milliseconds from its start (`endMs > startMs`). */
data class SkipSegment(val kind: SegmentKind, val startMs: Long, val endMs: Long, val source: SegmentSource) {
    val durationMs: Long get() = endMs - startMs

    fun contains(positionMs: Long): Boolean = positionMs in startMs until endMs
}

/** A Matroska chapter: where it starts and its display title (`null` when it has none). */
data class MediaChapter(val startMs: Long, val title: String?)
