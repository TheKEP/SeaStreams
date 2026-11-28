package com.seastreams.tv.domain.playback.skip

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Milestone 14: chapters + IntroDB → one segment per kind for the playing file. */
class SkipSegmentMergerTest {

    private val duration = 2_400_000L

    private fun chap(kind: SegmentKind, start: Long, end: Long) = SkipSegment(kind, start, end, SegmentSource.CHAPTERS)

    private fun online(kind: SegmentKind, start: Long, end: Long) = SkipSegment(kind, start, end, SegmentSource.INTRODB)

    @Test
    fun `episodes prefer chapters, movies prefer IntroDB, the other fills gaps`() {
        val chapters = listOf(chap(SegmentKind.INTRO, 60_000, 120_000))
        val introDb = listOf(online(SegmentKind.INTRO, 50_000, 110_000), online(SegmentKind.CREDITS, 2_300_000, 2_390_000))

        assertEquals(
            listOf(chapters[0], introDb[1]),
            SkipSegmentMerger.merge(chapters, introDb, isMovie = false, duration),
        )
        assertEquals(introDb, SkipSegmentMerger.merge(chapters, introDb, isMovie = true, duration))
    }

    @Test
    fun `credits overlapping the post-credits scene end where it starts`() {
        val introDb = listOf(online(SegmentKind.CREDITS, 2_000_000, 2_350_000), online(SegmentKind.POST_CREDITS, 2_300_000, 2_380_000))

        assertEquals(
            listOf(online(SegmentKind.CREDITS, 2_000_000, 2_300_000), introDb[1]),
            SkipSegmentMerger.merge(emptyList(), introDb, isMovie = true, duration),
        )
    }

    @Test
    fun `IntroDB times from a longer cut are dropped, small overruns are clamped`() {
        val introDb = listOf(
            online(SegmentKind.CREDITS, 2_300_000, duration + 1_500),
            online(SegmentKind.POST_CREDITS, 2_390_000, duration + 60_000),
        )

        assertEquals(
            listOf(online(SegmentKind.CREDITS, 2_300_000, duration)),
            SkipSegmentMerger.merge(emptyList(), introDb, isMovie = true, duration),
        )
    }

    @Test
    fun `an intro in the second half or credits in the first half are dropped`() {
        val introDb = listOf(online(SegmentKind.INTRO, 1_300_000, 1_360_000), online(SegmentKind.CREDITS, 100_000, 160_000))

        assertEquals(emptyList<SkipSegment>(), SkipSegmentMerger.merge(emptyList(), introDb, isMovie = false, duration))
    }

    @Test
    fun `nothing without a known duration`() {
        assertEquals(emptyList<SkipSegment>(), SkipSegmentMerger.merge(listOf(chap(SegmentKind.INTRO, 0, 60_000)), emptyList(), false, 0))
    }
}
