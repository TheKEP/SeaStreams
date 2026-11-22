package com.seastreams.tv.player.subtitle.sync

import com.seastreams.tv.domain.filter.SdhSubtitleCleaner
import com.seastreams.tv.player.subtitle.TimedCue

/**
 * The external cues auto-sync times (PRD §9): spoken lines only. Sound-effect captions
 * (`[DOOR SLAMS]`), music-note-only lines and on-screen signs held for [MAX_CUE_US] or longer mark no
 * speech, so they would add false activity for the audio path and false onsets for both. Analysis
 * only: what is shown is untouched, and the Clean SDH toggle doesn't matter. When fewer than
 * [MIN_KEPT] cues would remain (a file of mostly captions, or cues without text), every cue is kept.
 */
object SyncCueFilter {

    const val MAX_CUE_US = 10_000_000L
    const val MIN_KEPT = 20

    fun dialogue(cues: List<TimedCue>): List<TimedCue> {
        val kept = cues.filter { cue ->
            cue.endUs - cue.startUs < MAX_CUE_US && SdhSubtitleCleaner.clean(cue.text).any(Char::isLetterOrDigit)
        }
        return if (kept.size >= MIN_KEPT) kept else cues
    }
}
