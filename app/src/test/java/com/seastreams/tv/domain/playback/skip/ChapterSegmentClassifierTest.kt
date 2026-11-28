package com.seastreams.tv.domain.playback.skip

import com.seastreams.tv.domain.playback.skip.ChapterSegmentClassifier.Type
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Milestone 14: Matroska chapter titles → skip segments. */
class ChapterSegmentClassifierTest {

    private fun ch(sec: Long, title: String?) = MediaChapter(sec * 1_000, title)

    private fun seg(kind: SegmentKind, startSec: Long, endSec: Long) = SkipSegment(kind, startSec * 1_000, endSec * 1_000, SegmentSource.CHAPTERS)

    @Test
    fun `strict titles match, scene names don't`() {
        assertEquals(Type.OPENING, ChapterSegmentClassifier.classifyTitle("OP"))
        assertEquals(Type.OPENING, ChapterSegmentClassifier.classifyTitle("Opening Credits"))
        assertEquals(Type.CREDITS, ChapterSegmentClassifier.classifyTitle("ED 2"))
        assertEquals(Type.CREDITS, ChapterSegmentClassifier.classifyTitle("End Credits"))
        assertEquals(Type.RECAP, ChapterSegmentClassifier.classifyTitle("Previously on Lost"))
        assertEquals(Type.INTRO, ChapterSegmentClassifier.classifyTitle("Intro"))
        assertEquals(Type.POST_CREDITS, ChapterSegmentClassifier.classifyTitle("Post-Credits Scene"))
        assertEquals(Type.POST_CREDITS, ChapterSegmentClassifier.classifyTitle("Mid credits"))
        assertNull(ChapterSegmentClassifier.classifyTitle("The Opening"))
        assertNull(ChapterSegmentClassifier.classifyTitle("Alternate Ending"))
        assertNull(ChapterSegmentClassifier.classifyTitle("Chapter 3"))
        assertNull(ChapterSegmentClassifier.classifyTitle(null))
    }

    @Test
    fun `an episode's intro, credits and post-credits scene become segments`() {
        val chapters = listOf(ch(0, "Prologue"), ch(60, "Intro"), ch(150, "Part A"), ch(1_200, "Credits"), ch(1_290, "Post-Credits"), ch(1_320, "Preview"))

        assertEquals(
            listOf(seg(SegmentKind.INTRO, 60, 150), seg(SegmentKind.CREDITS, 1_200, 1_290), seg(SegmentKind.POST_CREDITS, 1_290, 1_320)),
            ChapterSegmentClassifier.classify(chapters, 1_400_000),
        )
    }

    @Test
    fun `Intro is ignored when an OP chapter exists`() {
        val chapters = listOf(ch(0, "Intro"), ch(30, "OP"), ch(120, "Part A"))

        assertEquals(listOf(seg(SegmentKind.INTRO, 30, 120)), ChapterSegmentClassifier.classify(chapters, 1_400_000))
    }

    @Test
    fun `too short or too long chapters are dropped, adjacent credits merge`() {
        val chapters = listOf(ch(0, "Recap"), ch(3, "Intro"), ch(400, "Story"), ch(1_000, "Credits"), ch(1_060, "Credits 2"), ch(1_100, "Story"))

        // Recap lasts 3 s (< 5 s), intro 397 s (> 240 s); the two credits chapters merge.
        assertEquals(listOf(seg(SegmentKind.CREDITS, 1_000, 1_100)), ChapterSegmentClassifier.classify(chapters, 1_400_000))
    }

    @Test
    fun `no chapters or an unknown duration yields nothing`() {
        assertEquals(emptyList<SkipSegment>(), ChapterSegmentClassifier.classify(listOf(ch(0, "OP")), 100_000))
        assertEquals(emptyList<SkipSegment>(), ChapterSegmentClassifier.classify(listOf(ch(0, "OP"), ch(60, "A")), 0))
    }
}
