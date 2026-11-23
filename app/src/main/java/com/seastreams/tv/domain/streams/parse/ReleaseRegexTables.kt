package com.seastreams.tv.domain.streams.parse

/**
 * The release-name tag tables, ported from AIOStreams `parser/regex.ts` (`PARSE_REGEX`). Each table is
 * ordered: single-value attributes (resolution, quality, encode) take the first match, multi-value ones
 * (visual/audio tags, channels, languages) keep every match in table order.
 *
 * Every pattern is wrapped like AIOStreams' `createRegex`: it must start the text or follow a separator and
 * end before a separator or the end. Java can't look behind by an unbounded `.*`, so AIOStreams'
 * `(?<!remux.*)` / `(?<!dvd.*)` become bounded `.{0,255}` look-behinds (release names are shorter).
 */
object ReleaseRegexTables {

    /** One tag: its canonical name and the pattern that detects it. */
    class Tag(val name: String, val regex: Regex)

    private fun tag(name: String, pattern: String) =
        Tag(name, Regex("(?<![^\\s\\[(_\\-.,])($pattern)(?=[\\s\\[(\\)\\]_.\\-,]|$)", RegexOption.IGNORE_CASE))

    private fun language(name: String, pattern: String) = tag(name, "$pattern(?![ .\\-_]?sub(title)?s?)")

    val RESOLUTIONS: List<Tag> = listOf(
        tag("2160p", "(bd|hd|m)?(4k|2160(p|i)?)|u(ltra)?[ .\\-_]?hd|3840\\s?x\\s?(\\d{4})"),
        tag("1440p", "(bd|hd|m)?(1440(p|i)?)|2k|w?q(uad)?[ .\\-_]?hd|2560\\s?x(\\d{4})"),
        tag("1080p", "(bd|hd|m)?(1080(p|i)?)|f(ull)?[ .\\-_]?hd|1920\\s?x\\s?(\\d{3,4})"),
        tag("720p", "(bd|hd|m)?((720|800)(p|i)?)|hd|1280\\s?x\\s?(\\d{3,4})"),
        tag("576p", "(bd|hd|m)?((576|534)(p|i)?)"),
        tag("480p", "(bd|hd|m)?(480(p|i)?)|sd"),
        tag("360p", "(bd|hd|m)?(360(p|i)?)"),
        tag("240p", "(bd|hd|m)?(240(p|i)?)"),
        tag("144p", "(bd|hd|m)?(144(p|i)?)"),
    )

    val QUALITIES: List<Tag> = listOf(
        tag("BluRay REMUX", "(?<!dvd.{0,255})(bd|br|b|uhd)?remux(?!.*dvd)"),
        tag("BluRay", "(?<!remux.{0,255})((bd|blu[ .\\-_]?ray)([ .\\-_]?rip)?|br[ .\\-_]?rip)(?!.*remux)"),
        tag("WEB-DL", "web[ .\\-_]?(dl)?(?![ .\\-_]?(rip|DLRip|cam))"),
        tag("WEBRip", "web[ .\\-_]?rip"),
        tag("HDRip", "hd[ .\\-_]?rip|web[ .\\-_]?dl[ .\\-_]?rip"),
        tag("HC HD-Rip", "hc|hd[ .\\-_]?rip"),
        tag("DVD REMUX", "(hd[ .\\-_]?)?dvd.*remux|remux.*(hd[ .\\-_]?)?dvd"),
        tag("DVDRip", "dvd[ .\\-_]?(rip|mux|r|full|5|9)?"),
        tag("HDTV", "(hd|pd)tv|tv[ .\\-_]?rip|hdtv[ .\\-_]?rip|dsr(ip)?|sat[ .\\-_]?rip"),
        tag("CAM", "cam|hdcam|cam[ .\\-_]?rip"),
        tag("TS", "telesync|ts|hd[ .\\-_]?ts|pdvd|predvd(rip)?"),
        tag("TC", "telecine|tc|hd[ .\\-_]?tc"),
        tag("SCR", "((dvd|bd|web|hd)?[ .\\-_]?)?(scr(eener)?)"),
    )

    val VISUAL_TAGS: List<Tag> = listOf(
        tag("10bit", "10[ .\\-_]?bit|hi10p?"),
        tag("HDR10+", "hdr[ .\\-_]?10[ .\\-_]?(p(lus)?|[+])"),
        tag("HDR10", "hdr[ .\\-_]?10(?![ .\\-_]?(?:\\+|p(?:lus)?|bit|hi))"),
        tag("HDR", "hdr(?![ .\\-_]?(?:10(?![ .\\-_]?(?:bit|hi))|\\+|p(?:lus)?))"),
        tag("HLG", "hlg"),
        tag("DV", "do?(lby)?[ .\\-_]?vi?(sion)?(?:[ .\\-_]?atmos)?|dv"),
        tag("3D", "(bd)?(3|three)[ .\\-_]?(d(imension)?(al)?)"),
        tag("IMAX", "imax"),
        tag("AI", "ai|ai(enhanced?|re[ .\\-_]?graded?)"),
        tag("Upscaled", "(ai)?(uprez|ups(uhd|cal(ed?|ing)(uhd)?))(ai)?"),
        tag("SDR", "sdr"),
        tag("H-OU", "h?(alf)?[ .\\-_]?(ou|over[ .\\-_]?under)"),
        tag("H-SBS", "h?(alf)?[ .\\-_]?(sbs|side[ .\\-_]?by[ .\\-_]?side)"),
    )

    val AUDIO_TAGS: List<Tag> = listOf(
        tag("Atmos", "atmos|ddpa\\d?"),
        tag(
            "DD+",
            "(d(olby)?[ .\\-_]?d(igital)?[ .\\-_]?((p(lus)?|\\+)a?)(?:[ .\\-_]?(2[ .\\-_]?0|5[ .\\-_]?1|7[ .\\-_]?1))?)|e[ .\\-_]?ac[ .\\-_]?3",
        ),
        tag("DD", "(d(olby)?[ .\\-_]?d(igital)?(?:[ .\\-_]?(5[ .\\-_]?1|7[ .\\-_]?1|2[ .\\-_]?0?))?)|(?<!e[ .\\-_]?)ac[ .\\-_]?3"),
        tag("DTS:X", "dts[ .\\-:_]?x"),
        tag("DTS-HD MA", "dts[ .\\-_]?hd[ .\\-_]?ma"),
        tag("DTS-HD", "dts[ .\\-_]?hd(?![ .\\-_]?ma)"),
        tag("DTS-ES", "dts[ .\\-_]?es"),
        tag("DTS", "dts(?![ .\\-:_]?(x(?=[\\s\\[(\\)\\]_.\\-,]|$)|hd[ .\\-_]?(ma)?|es))"),
        tag("TrueHD", "true[ .\\-_]?hd"),
        tag("PCM", "l?pcm"),
        tag("OPUS", "opus"),
        tag("AAC", "q?aac(?:[ .\\-_]?2)?"),
        tag("FLAC", "flac(?:[ .\\-_]?(lossless|2\\.0|x[2-4]))?"),
    )

    val AUDIO_CHANNELS: List<Tag> = listOf(
        tag("2.0", "(d(olby)?[ .\\-_]?d(igital)?)?2[ .\\-_]?0(ch)?"),
        tag("5.1", "(d(olby)?[ .\\-_]?d(igital)?[ .\\-_]?((p(lus)?|\\+)a?)?)?5[ .\\-_]?1(ch)?"),
        tag("6.1", "(d(olby)?[ .\\-_]?d(igital)?[ .\\-_]?((p(lus)?|\\+)a?)?)?6[ .\\-_]?1(ch)?"),
        tag("7.1", "(d(olby)?[ .\\-_]?d(igital)?[ .\\-_]?((p(lus)?|\\+)a?)?)?7[ .\\-_]?1(ch)?"),
    )

    val ENCODES: List<Tag> = listOf(
        tag("HEVC", "hevc[ .\\-_]?(10)?|[xh][ .\\-_]?265"),
        tag("AVC", "avc|[xh][ .\\-_]?264"),
        tag("AV1", "av1"),
        tag("VC-1", "vc[ .\\-_]?1"),
        tag("XviD", "xvid"),
        tag("DivX", "divx|dvix"),
        tag("MPEG-4", "mpeg[ .\\-_]?4"),
    )

    /** Language tags, in [LanguageTable] order (which is why English sorts before Italian in a release name). */
    val LANGUAGES: List<Tag> = LanguageTable.ALL.mapNotNull { entry -> entry.pattern?.let { language(entry.name, it) } }

    /** Every resolution / quality / … value a filter can name, including `Unknown` (attribute missing). */
    val RESOLUTION_NAMES: List<String> = RESOLUTIONS.map { it.name } + UNKNOWN
    val QUALITY_NAMES: List<String> = QUALITIES.map { it.name } + UNKNOWN

    /** AIOStreams' synthesised tags (`HDR+DV`, `DV Only`, `HDR Only`) come first, as in its `VISUAL_TAGS`. */
    val VISUAL_TAG_NAMES: List<String> = FAKE_VISUAL_TAGS + listOf("HDR10+", "HDR10", "DV", "HDR", "HLG", "10bit", "3D", "IMAX", "AI", "Upscaled", "SDR", "H-OU", "H-SBS", UNKNOWN)
    val AUDIO_TAG_NAMES: List<String> = AUDIO_TAGS.map { it.name } + UNKNOWN
    val AUDIO_CHANNEL_NAMES: List<String> = AUDIO_CHANNELS.map { it.name } + UNKNOWN
    val ENCODE_NAMES: List<String> = listOf("AV1", "HEVC", "AVC", "VC-1", "XviD", "DivX", "MPEG-4", UNKNOWN)
    val LANGUAGE_NAMES: List<String> = LanguageTable.ALL.map { it.name } + UNKNOWN

    const val UNKNOWN = "Unknown"
}

/** Tags derived from the parsed ones for filtering and sorting only, never shown as parsed tags. */
val FAKE_VISUAL_TAGS: List<String> = listOf("HDR+DV", "DV Only", "HDR Only")

/** The first tag of [tags] that matches [text], or `null`. */
internal fun firstTag(text: String, tags: List<ReleaseRegexTables.Tag>): String? =
    tags.firstOrNull { it.regex.containsMatchIn(text) }?.name

/** Every tag of [tags] that matches [text], in table order. */
internal fun allTags(text: String, tags: List<ReleaseRegexTables.Tag>): List<String> =
    tags.filter { it.regex.containsMatchIn(text) }.map { it.name }

/** [visualTags] plus the synthesised `HDR+DV` / `DV Only` / `HDR Only` tag that describes them, if any. */
fun withFakeVisualTags(visualTags: List<String>): List<String> {
    val dv = "DV" in visualTags
    val hdr = visualTags.any { it.startsWith("HDR") }
    val fake = when {
        dv && hdr -> "HDR+DV"
        dv -> "DV Only"
        hdr -> "HDR Only"
        else -> null
    }
    return if (fake == null) visualTags else visualTags + fake
}
