package com.seastreams.tv.domain.filter

/**
 * Clean SDH (PRD §9): strips hearing-impaired annotations from a subtitle cue, leaving the dialogue.
 *
 * - Sound cues in brackets or parentheses — `[SIGHS]`, `(DOOR CREAKS)`, `[man on radio]` — including
 *   ones that wrap across the cue's lines.
 * - Music notes `♪` / `♫` (the lyrics between them are kept: they are sung dialogue).
 * - Upper-case speaker labels at the start of a line: `JOHN:`, `MAN 2:`, `DR. SMITH:`, `- JOHN:`.
 *   Mixed-case text such as `Note: …` or times like `10:30` are left alone.
 *
 * Lines left empty (or holding only a dialogue dash / punctuation) are dropped; a cue that was only
 * annotations becomes `""`. Pure Kotlin, safe to call for every cue on the playback path.
 */
object SdhSubtitleCleaner {

    private val BRACKETED = Regex("""\[[^\[\]]*]|\([^()]*\)""")
    private val MUSIC = Regex("[♪♫♬♩]+")
    private val SPEAKER_LABEL = Regex("""^(\s*[-–—]\s*)?([\p{Lu}\p{Nd}][\p{Lu}\p{Nd} .'&/-]{0,40}?)\s*:(?=\s|$)""")
    private val WHITESPACE = Regex("""[ \t ]+""")
    private val ONLY_PUNCTUATION = Regex("""^[\s\p{Punct}–—…]*$""")
    private val DASH_ONLY = Regex("""^[-–—]\s*$""")

    fun clean(text: String): String {
        if (text.isBlank()) return ""
        var result = text.replace("\r\n", "\n")
        // Nested or adjacent brackets: repeat until nothing more matches.
        while (true) {
            val next = BRACKETED.replace(result, "")
            if (next == result) break
            result = next
        }
        result = MUSIC.replace(result, "")
        return result.split('\n')
            .map(::stripSpeakerLabel)
            .map { WHITESPACE.replace(it, " ").trim() }
            .filterNot { it.isEmpty() || DASH_ONLY.matches(it) || ONLY_PUNCTUATION.matches(it) }
            .joinToString("\n")
    }

    private fun stripSpeakerLabel(line: String): String {
        val match = SPEAKER_LABEL.find(line) ?: return line
        val label = match.groupValues[2]
        if (label.count { it.isUpperCase() } < MIN_LABEL_LETTERS) return line
        val dash = match.groupValues[1].trim()
        val rest = line.substring(match.range.last + 1).trimStart()
        return if (dash.isEmpty()) rest else "$dash $rest"
    }

    private const val MIN_LABEL_LETTERS = 2
}
