package com.seastreams.tv.domain.playback.skip

/**
 * Turns Matroska chapter titles into [SkipSegment]s (PRD §9). Only titles that are essentially the label itself
 * match (`OP`, `Opening 2`, `Ending`, `End Credits`, `Recap`, `Previously on…`, `Intro`), so scene-named chapters
 * such as "The Opening" or "Alternate Ending" never become skips. `Intro` counts as the intro only when no
 * `OP`/`Opening` chapter exists. Unlike the reference player, post-, mid- and after-credits chapters are kept as
 * [SegmentKind.POST_CREDITS], the target of `Skip to post-credits`. Adjacent chapters of one kind (credits split in
 * two) merge into one segment.
 */
object ChapterSegmentClassifier {

    fun classify(chapters: List<MediaChapter>, durationMs: Long): List<SkipSegment> {
        if (chapters.size < 2 || durationMs <= 0) return emptyList()
        val sorted = chapters.filter { it.startMs in 0 until durationMs }.sortedBy { it.startMs }
        val typed = sorted.mapIndexed { index, chapter ->
            val end = sorted.getOrNull(index + 1)?.startMs ?: durationMs
            Typed(classifyTitle(chapter.title), chapter.startMs, end)
        }
        val hasOpening = typed.any { it.type == Type.OPENING }
        val kinds = typed.map { chapter ->
            val kind = when (chapter.type) {
                Type.OPENING -> SegmentKind.INTRO
                Type.INTRO -> if (hasOpening) null else SegmentKind.INTRO
                Type.RECAP -> SegmentKind.RECAP
                Type.CREDITS -> SegmentKind.CREDITS
                Type.POST_CREDITS -> SegmentKind.POST_CREDITS
                null -> null
            }
            Triple(kind, chapter.startMs, chapter.endMs)
        }
        return mergeAdjacent(kinds).filter { it.durationMs in boundsFor(it.kind) }
    }

    private fun mergeAdjacent(kinds: List<Triple<SegmentKind?, Long, Long>>): List<SkipSegment> {
        val out = mutableListOf<SkipSegment>()
        var previousKind: SegmentKind? = null
        for ((kind, start, end) in kinds) {
            val last = out.lastOrNull()
            if (kind != null && kind == previousKind && last != null && last.endMs == start) {
                out[out.lastIndex] = last.copy(endMs = end)
            } else if (kind != null) {
                out += SkipSegment(kind, start, end, SegmentSource.CHAPTERS)
            }
            previousKind = kind
        }
        return out
    }

    internal fun classifyTitle(title: String?): Type? {
        if (title.isNullOrBlank()) return null
        // Before CREDITS: "Post-Credits Scene" must never read as the credits themselves.
        return when {
            POST_CREDITS.containsMatchIn(title) -> Type.POST_CREDITS
            OPENING.matches(title) -> Type.OPENING
            ENDING.matches(title) || CREDITS.matches(title) -> Type.CREDITS
            RECAP.matches(title) -> Type.RECAP
            INTRO.matches(title) -> Type.INTRO
            else -> null
        }
    }

    private fun boundsFor(kind: SegmentKind): LongRange = when (kind) {
        SegmentKind.INTRO, SegmentKind.RECAP -> MIN_SEGMENT_MS..MAX_INTRO_MS
        SegmentKind.CREDITS -> MIN_SEGMENT_MS..MAX_CREDITS_MS
        SegmentKind.POST_CREDITS -> MIN_SCENE_MS..MAX_CREDITS_MS
    }

    internal enum class Type { OPENING, INTRO, RECAP, CREDITS, POST_CREDITS }

    private data class Typed(val type: Type?, val startMs: Long, val endMs: Long)

    private val OPENING = Regex("^\\s*(op|opening)(\\s*(credits|theme))?\\s*\\d*\\s*$", RegexOption.IGNORE_CASE)
    private val ENDING = Regex("^\\s*(ed|ending)(\\s*(credits|theme))?\\s*\\d*\\s*$", RegexOption.IGNORE_CASE)
    private val CREDITS = Regex("^\\s*((end|closing)\\s*)?credits\\s*\\d*\\s*$", RegexOption.IGNORE_CASE)
    private val RECAP = Regex("^\\s*(recap|previously(\\s+on.*)?)\\s*$", RegexOption.IGNORE_CASE)
    private val INTRO = Regex("^\\s*intro\\s*\\d*\\s*$", RegexOption.IGNORE_CASE)
    private val POST_CREDITS = Regex("(post|mid|after)[\\s-]*credits?", RegexOption.IGNORE_CASE)

    private const val MIN_SEGMENT_MS = 5_000L
    private const val MIN_SCENE_MS = 1_000L
    private const val MAX_INTRO_MS = 240_000L
    private const val MAX_CREDITS_MS = 900_000L
}
