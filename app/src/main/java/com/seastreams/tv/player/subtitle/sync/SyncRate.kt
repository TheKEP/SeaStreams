package com.seastreams.tv.player.subtitle.sync

import com.seastreams.tv.player.subtitle.TimedCue

/**
 * The clock an external subtitle file was timed on, relative to the video (PRD §9 Auto-Sync). A file
 * timed for a 25 fps PAL release runs 4 % fast against a 23.976 fps video and drifts ~2.6 s a minute,
 * which no single offset fixes. Media time = subtitle time × [factor] − offset; scaling is about
 * `t = 0`, so the offset stays the file's real intercept.
 *
 * The 1.001 family (23.976 ↔ 24 fps) is left to drift tracking: a 5-minute window can't tell it from
 * a constant offset. [family] groups rates of the same direction (`25→23.976` and `25→24` differ by
 * only 0.1 %), so they are never each other's rival.
 */
enum class SyncRate(val factor: Double, val label: String, val family: Int) {
    UNIT(1.0, "", 0),
    PAL_TO_FILM(25.0 / 23.976, "25→23.976 fps", 1),
    PAL_TO_24(25.0 / 24.0, "25→24 fps", 1),
    FILM_TO_PAL(23.976 / 25.0, "23.976→25 fps", -1),
    TWENTYFOUR_TO_PAL(24.0 / 25.0, "24→25 fps", -1),
    ;

    /** [cues] moved onto the video's clock (start and end × [factor]). */
    fun apply(cues: List<TimedCue>): List<TimedCue> =
        if (this == UNIT) cues else cues.map { TimedCue(Math.round(it.startUs * factor), Math.round(it.endUs * factor), it.text) }

    /** The subtitle-file time whose cue shows at video-clock time [videoUs] (offset already added). */
    fun toSubtitleUs(videoUs: Long): Long = if (this == UNIT) videoUs else Math.round(videoUs / factor)
}
