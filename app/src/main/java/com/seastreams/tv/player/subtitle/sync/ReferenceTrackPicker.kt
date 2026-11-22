package com.seastreams.tv.player.subtitle.sync

import com.seastreams.tv.domain.playback.SubtitleOption
import com.seastreams.tv.domain.playback.SubtitleSource
import java.util.Locale
import java.util.MissingResourceException

/**
 * The embedded track auto-sync times against. [otherLanguage] is its language (ISO 639-2, or `null`
 * when unknown) when it isn't known to be the external track's language: such a reference needs two
 * agreeing passes, since a translation splits and merges lines differently.
 */
data class ReferencePick(val option: SubtitleOption, val sameLanguage: Boolean) {
    val otherLanguage: String? get() = if (sameLanguage) null else ReferenceTrackPicker.normalize(option.language) ?: UNKNOWN_LANGUAGE

    private companion object {
        const val UNKNOWN_LANGUAGE = "other language"
    }
}

/** Picks the embedded subtitle track auto-sync may use as a timing reference for an external track. */
object ReferenceTrackPicker {

    /**
     * The first full embedded track in [options] whose language matches [language] (`en`, `eng` and
     * `en-US` all match); otherwise the first full embedded track of any language (also when [language]
     * is unknown): a foreign track of the same release still follows the speech far better than audio
     * voice activity. Partial tracks ([isPartial]) are never used: they show only a few cues, so when
     * only partial tracks exist this is `null` and auto-sync goes straight to audio.
     */
    fun pick(language: String?, options: List<SubtitleOption>): ReferencePick? {
        val full = options.filter { it.source == SubtitleSource.EMBEDDED && !isPartial(it) }
        val wanted = normalize(language)
        wanted?.let { code -> full.firstOrNull { normalize(it.language) == code }?.let { return ReferencePick(it, sameLanguage = true) } }
        return full.firstOrNull()?.let { ReferencePick(it, sameLanguage = false) }
    }

    /**
     * Forced, signs/songs, foreign-parts-only and commentary tracks, judged by label: Matroska files often
     * name them ("English (Signs & Songs)") without setting role or forced flags. `PlayerTracks` already
     * adds `Forced` to the label of tracks flagged forced.
     */
    fun isPartial(option: SubtitleOption): Boolean = PARTIAL.any { option.label.contains(it, ignoreCase = true) }

    /** ISO 639-2 code of [code], or `null` when unknown/undetermined. */
    fun normalize(code: String?): String? {
        val tag = code?.trim()?.takeIf { it.isNotEmpty() && !it.equals(UNDETERMINED, ignoreCase = true) } ?: return null
        val locale = Locale.forLanguageTag(tag.replace('_', '-'))
        val iso3 = try {
            locale.isO3Language
        } catch (_: MissingResourceException) {
            ""
        }
        val code3 = iso3.ifEmpty { locale.language.ifEmpty { tag.lowercase(Locale.ROOT) } }
        return BIBLIOGRAPHIC[code3] ?: code3
    }

    /** ISO 639-2/B codes (common in Matroska) → the 639-2/T codes `Locale` reports. */
    private val BIBLIOGRAPHIC = mapOf(
        "alb" to "sqi", "arm" to "hye", "baq" to "eus", "bur" to "mya", "chi" to "zho", "cze" to "ces",
        "dut" to "nld", "fre" to "fra", "geo" to "kat", "ger" to "deu", "gre" to "ell", "ice" to "isl",
        "mac" to "mkd", "mao" to "mri", "may" to "msa", "per" to "fas", "rum" to "ron", "slo" to "slk",
        "tib" to "bod", "wel" to "cym",
    )

    private val PARTIAL = listOf("forced", "signs", "songs", "foreign", "commentary")
    private const val UNDETERMINED = "und"
}
