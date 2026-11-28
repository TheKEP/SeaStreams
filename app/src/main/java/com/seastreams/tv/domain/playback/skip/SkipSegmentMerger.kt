package com.seastreams.tv.domain.playback.skip

/**
 * One segment per [SegmentKind] for the playing file (PRD §9), from its chapters and IntroDB:
 * - **Priority:** episodes prefer the file's chapters (they describe this exact release), movies prefer IntroDB
 *   (its credits are already split around post-credits scenes). The other source fills any kind still missing.
 * - **Sanity:** an IntroDB segment running more than [DURATION_TOLERANCE_MS] past this file's end belongs to
 *   another cut and is dropped; intros and recaps must start in the first half of the file, credits and
 *   post-credits scenes in the second half. Everything is clamped to the duration.
 * - **Credits never swallow a scene:** credits overlapping the post-credits scene end where the scene starts.
 */
object SkipSegmentMerger {

    fun merge(chapters: List<SkipSegment>, online: List<SkipSegment>, isMovie: Boolean, durationMs: Long): List<SkipSegment> {
        if (durationMs <= 0) return emptyList()
        val fromChapters = chapters.mapNotNull { sanitize(it, durationMs) }
        val fromOnline = online.mapNotNull { sanitize(it, durationMs) }
        val (primary, secondary) = if (isMovie) fromOnline to fromChapters else fromChapters to fromOnline
        val chosen = SegmentKind.entries.mapNotNull { kind ->
            primary.firstOrNull { it.kind == kind } ?: secondary.firstOrNull { it.kind == kind }
        }
        val scene = chosen.firstOrNull { it.kind == SegmentKind.POST_CREDITS }
        return chosen.mapNotNull { segment ->
            if (segment.kind != SegmentKind.CREDITS || scene == null) return@mapNotNull segment
            val overlaps = scene.startMs < segment.endMs && scene.endMs > segment.startMs
            if (!overlaps) segment else segment.copy(endMs = scene.startMs).takeIf { it.durationMs >= MIN_TRIMMED_MS }
        }.sortedBy { it.startMs }
    }

    private fun sanitize(segment: SkipSegment, durationMs: Long): SkipSegment? {
        if (segment.startMs < 0 || segment.endMs <= segment.startMs || segment.startMs >= durationMs) return null
        if (segment.source == SegmentSource.INTRODB && segment.endMs > durationMs + DURATION_TOLERANCE_MS) return null
        val half = durationMs / 2
        val placed = when (segment.kind) {
            SegmentKind.INTRO, SegmentKind.RECAP -> segment.startMs < half
            SegmentKind.CREDITS, SegmentKind.POST_CREDITS -> segment.startMs >= half
        }
        return segment.copy(endMs = segment.endMs.coerceAtMost(durationMs)).takeIf { placed && it.endMs > it.startMs }
    }

    /** IntroDB times may run this far past the file's end (rounding, a slightly shorter encode). */
    const val DURATION_TOLERANCE_MS = 2_000L
    private const val MIN_TRIMMED_MS = 1_000L
}
