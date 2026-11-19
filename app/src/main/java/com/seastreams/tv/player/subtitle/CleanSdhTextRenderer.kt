package com.seastreams.tv.player.subtitle

import com.seastreams.tv.domain.filter.SdhSubtitleCleaner

/** One timed cue of an external (addon) subtitle file. Times in microseconds. */
data class TimedCue(val startUs: Long, val endUs: Long, val text: String)

/**
 * Turns the cues active at a moment into the text the player overlay draws with `SubtitleCueText`
 * (so the Settings preview and playback share one renderer). With Clean SDH on, every cue goes through
 * [SdhSubtitleCleaner] as it is shown, so toggling it mid-playback applies to the very next cue.
 */
class CleanSdhTextRenderer {

    @Volatile
    var cleanSdh: Boolean = false

    /** Joins [cueTexts] (one per active cue) into display lines; `null` when nothing is left to show. */
    fun render(cueTexts: List<CharSequence?>): String? {
        val joined = cueTexts.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }.joinToString("\n")
        if (joined.isEmpty()) return null
        val shown = if (cleanSdh) SdhSubtitleCleaner.clean(joined) else joined
        return shown.ifBlank { null }
    }
}

/**
 * The parsed cues of one external subtitle file, looked up by playback position. A sync offset is
 * applied by the caller: `+` (subtitles earlier) looks further ahead, `−` looks behind.
 */
class ExternalSubtitleTimeline(cues: List<TimedCue>) {

    private val sorted = cues.filter { it.endUs > it.startUs }.sortedBy { it.startUs }
    private val maxDurationUs = sorted.maxOfOrNull { it.endUs - it.startUs } ?: 0L

    val isEmpty: Boolean get() = sorted.isEmpty()

    /** Every cue in start order (auto-sync rasterises them). */
    val cues: List<TimedCue> get() = sorted

    /** Texts of every cue showing at [positionUs], in start order. */
    fun textsAt(positionUs: Long): List<String> {
        if (sorted.isEmpty()) return emptyList()
        // Last cue starting at or before the position; earlier cues can only overlap within maxDuration.
        var low = 0
        var high = sorted.lastIndex
        var last = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (sorted[mid].startUs <= positionUs) {
                last = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        if (last < 0) return emptyList()
        val result = ArrayList<String>(2)
        var i = last
        while (i >= 0 && positionUs - sorted[i].startUs <= maxDurationUs) {
            val cue = sorted[i]
            if (positionUs < cue.endUs) result.add(cue.text)
            i--
        }
        result.reverse()
        return result
    }
}

/**
 * External subtitle sync offset (PRD §9): `0.1s` steps within `±60.0s`, session-only. Applied as a
 * shift of every cue's start/end (in µs) before [CleanSdhTextRenderer] sees the text; auto-sync seeds it.
 */
object SubtitleOffset {
    const val STEP_MS = 100L
    const val LIMIT_MS = 60_000L

    fun step(currentMs: Long, direction: Int): Long =
        clamp(currentMs + direction.coerceIn(-1, 1) * STEP_MS)

    /** Snaps [offsetMs] to the `0.1s` grid within `±60.0s` (auto-detected values go through this too). */
    fun clamp(offsetMs: Long): Long = (Math.round(offsetMs / STEP_MS.toDouble()) * STEP_MS).coerceIn(-LIMIT_MS, LIMIT_MS)

    /** `+1.5s`, `-0.3s`, `0.0s`. */
    fun label(offsetMs: Long): String {
        val tenths = offsetMs / STEP_MS
        val sign = when {
            tenths > 0 -> "+"
            tenths < 0 -> "-"
            else -> ""
        }
        val abs = kotlin.math.abs(tenths)
        return "$sign${abs / TENTHS_PER_SECOND}.${abs % TENTHS_PER_SECOND}s"
    }

    private const val TENTHS_PER_SECOND = 10
}
