package com.seastreams.tv.domain.playback.skip

import com.seastreams.tv.core.datastore.PlayerSettings
import com.seastreams.tv.core.datastore.SkipPromptMode

/** The five player prompts (PRD §9 *Skip & Next Episode Prompts*). */
enum class PromptKind(val label: String) {
    SKIP_INTRO("Skip intro"),
    SKIP_RECAP("Skip recap"),
    SKIP_TO_POST_CREDITS("Skip to post-credits"),
    SKIP_CREDITS("Skip credits"),
    NEXT_EPISODE("Next episode"),
}

/**
 * What the player may offer at one position. [anchorMs] identifies the stretch it belongs to (a segment's start,
 * or where the Next episode window opened), so a prompt's entry window and auto-skip happen once per stretch.
 * [targetMs] is where a skip seeks (`null` for Next episode). [auto] = act without a press (Auto-skip, or credits
 * auto-skip handing straight to the next episode). [countdown] = Next episode counts down, then plays on its own.
 */
data class Prompt(
    val kind: PromptKind,
    val anchorMs: Long,
    val targetMs: Long?,
    val auto: Boolean = false,
    val countdown: Boolean = false,
)

/**
 * Picks the prompt for the playing position from the merged [SkipSegment]s (PRD §9):
 * - **Skip intro / recap:** inside the segment until [END_GUARD_MS] before it ends; seeks to its end.
 * - **Credits:** `Skip to post-credits` when a post-credits scene is known (seeks to it). Otherwise a movie's
 *   `Skip credits` seeks to the end (the movie finishes), and an episode's does only when at least
 *   [MIN_TAIL_MS] of programme follows the credits (seeks past them); with less, the episode's credits offer
 *   Next episode instead.
 * - **Next episode** (episodes with a next pick only): from the credits (or once a post-credits scene ends);
 *   with no credits data, the last [FALLBACK_WINDOW_MS]. It counts down only when it comes from credits data and
 *   Auto-play next episode is on; the fallback window never counts down (the natural end still auto-plays).
 */
object PlayerPromptPolicy {

    fun prompt(
        positionMs: Long,
        durationMs: Long,
        segments: List<SkipSegment>,
        isEpisode: Boolean,
        hasNext: Boolean,
        settings: PlayerSettings,
    ): Prompt? {
        if (durationMs <= 0 || positionMs < 0 || positionMs >= durationMs) return null
        val credits = segments.firstOrNull { it.kind == SegmentKind.CREDITS }
        val scene = segments.firstOrNull { it.kind == SegmentKind.POST_CREDITS && (credits == null || it.startMs >= credits.startMs) }
        val introMode = settings.skipIntroRecap
        for (segment in segments) {
            if (segment.kind != SegmentKind.INTRO && segment.kind != SegmentKind.RECAP) continue
            if (introMode == SkipPromptMode.OFF || !inSkipWindow(segment, positionMs)) continue
            val kind = if (segment.kind == SegmentKind.INTRO) PromptKind.SKIP_INTRO else PromptKind.SKIP_RECAP
            return Prompt(kind, segment.startMs, segment.endMs, auto = introMode == SkipPromptMode.AUTO)
        }
        val canNext = isEpisode && hasNext && settings.nextEpisodePrompt
        if (credits != null && inSkipWindow(credits, positionMs)) {
            creditsPrompt(credits, scene, durationMs, isEpisode, hasNext, settings)?.let { return it }
        }
        if (!canNext) return null
        val opensAt = nextWindowStart(credits, scene, durationMs)
        if (positionMs < opensAt) return null
        // Only a window opened by credits data counts down; a long programme tail ends on its own.
        val fromCredits = credits != null && (scene != null || durationMs - credits.endMs < MIN_TAIL_MS)
        return Prompt(PromptKind.NEXT_EPISODE, opensAt, null, countdown = fromCredits && settings.autoPlayNextEpisode)
    }

    private fun creditsPrompt(
        credits: SkipSegment,
        scene: SkipSegment?,
        durationMs: Long,
        isEpisode: Boolean,
        hasNext: Boolean,
        settings: PlayerSettings,
    ): Prompt? {
        val mode = settings.skipCredits
        if (mode == SkipPromptMode.OFF) return null
        val auto = mode == SkipPromptMode.AUTO
        if (scene != null) return Prompt(PromptKind.SKIP_TO_POST_CREDITS, credits.startMs, scene.startMs, auto)
        if (!isEpisode) return Prompt(PromptKind.SKIP_CREDITS, credits.startMs, durationMs, auto)
        if (durationMs - credits.endMs >= MIN_TAIL_MS) return Prompt(PromptKind.SKIP_CREDITS, credits.startMs, credits.endMs, auto)
        // Short tail: Auto-skip with Auto-play next episode on goes straight to the next episode.
        if (auto && hasNext && settings.autoPlayNextEpisode) return Prompt(PromptKind.NEXT_EPISODE, credits.startMs, null, auto = true)
        return null
    }

    /** Where the Next episode window opens. */
    internal fun nextWindowStart(credits: SkipSegment?, scene: SkipSegment?, durationMs: Long): Long {
        val fallback = (durationMs - FALLBACK_WINDOW_MS).coerceAtLeast(0)
        return when {
            credits == null -> fallback
            scene != null -> scene.endMs.coerceAtMost(durationMs)
            durationMs - credits.endMs >= MIN_TAIL_MS -> maxOf(credits.endMs, fallback)
            else -> credits.startMs
        }
    }

    private fun inSkipWindow(segment: SkipSegment, positionMs: Long): Boolean =
        positionMs >= segment.startMs && positionMs < segment.endMs - END_GUARD_MS

    /** Skip prompts disappear this close to their segment's end: the skip would save nothing. */
    const val END_GUARD_MS = 2_000L

    /** An episode's Skip credits needs at least this much programme after the credits. */
    const val MIN_TAIL_MS = 20_000L

    /** Next episode's window without credits data: the last 30 s. */
    const val FALLBACK_WINDOW_MS = 30_000L
}
