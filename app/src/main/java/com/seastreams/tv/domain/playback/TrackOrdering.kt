package com.seastreams.tv.domain.playback

import com.seastreams.tv.core.datastore.SubtitleMode
import com.seastreams.tv.domain.usecase.subtitleLanguageMatches

/** Where a subtitle track comes from (Info overlay: `Embedded` / `External`). */
enum class SubtitleSource(val label: String) { EMBEDDED("Embedded"), EXTERNAL("External") }

/**
 * One choice in the player's Subtitles list. Embedded ids are `group:track` indices into ExoPlayer's
 * current tracks; external ids are the addon subtitle URL.
 */
data class SubtitleOption(val id: String, val label: String, val language: String?, val source: SubtitleSource)

/** One audio track of the playing stream (`group:track` id). */
data class AudioOption(val id: String, val label: String, val language: String?)

/** Language codes in tracks look like `en`, `eng`, `en-US` or `en_GB`; preferences are ISO 639-1. */
fun languageMatches(language: String?, preferred: String): Boolean {
    val base = language?.trim()?.substringBefore('-')?.substringBefore('_') ?: return false
    return subtitleLanguageMatches(base, preferred)
}

/** PRD §9 track lists: preferred language first (each group keeping its order), others optionally hidden. */
object TrackOrdering {

    fun subtitles(options: List<SubtitleOption>, preferred: String, hideNonPreferred: Boolean): List<SubtitleOption> {
        val (match, rest) = options.partition { languageMatches(it.language, preferred) }
        return if (hideNonPreferred) match else match + rest
    }

    fun audio(options: List<AudioOption>, preferred: String): List<AudioOption> {
        val (match, rest) = options.partition { languageMatches(it.language, preferred) }
        return match + rest
    }

    /**
     * The subtitle switched on when playback starts: with *Always On*, the first preferred-language
     * track (embedded tracks are listed before addon ones); with *Always Off*, none.
     */
    fun initialSubtitle(ordered: List<SubtitleOption>, mode: SubtitleMode, preferred: String): SubtitleOption? =
        if (mode == SubtitleMode.ALWAYS_ON) ordered.firstOrNull { languageMatches(it.language, preferred) } else null
}
