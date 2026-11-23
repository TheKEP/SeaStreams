package com.seastreams.tv.domain.streams.parse

import com.seastreams.tv.domain.streams.parse.ReleaseRegexTables.AUDIO_CHANNELS
import com.seastreams.tv.domain.streams.parse.ReleaseRegexTables.AUDIO_TAGS
import com.seastreams.tv.domain.streams.parse.ReleaseRegexTables.ENCODES
import com.seastreams.tv.domain.streams.parse.ReleaseRegexTables.LANGUAGES
import com.seastreams.tv.domain.streams.parse.ReleaseRegexTables.QUALITIES
import com.seastreams.tv.domain.streams.parse.ReleaseRegexTables.RESOLUTIONS
import com.seastreams.tv.domain.streams.parse.ReleaseRegexTables.VISUAL_TAGS

/**
 * Release-name parser (AIOStreams `FileParser`). AIOStreams reads the title, year, seasons/episodes, group and
 * editions with the `parse-torrent-title` library, which SeaStreams can't add, so [titleAndYear] and the small
 * patterns below cover the common scene / P2P / anime naming forms. The tags come from [ReleaseRegexTables], tested
 * against the name with the title cut out (so a film called *Dune* never reads as a tag) exactly as AIOStreams does.
 */
object ReleaseParser {

    fun parse(name: String): ReleaseInfo {
        val trimmed = name.trim()
        val extension = EXTENSION.find(trimmed)?.groupValues?.get(1)?.lowercase()
        val base = if (extension != null) trimmed.substring(0, trimmed.length - extension.length - 1) else trimmed
        val group = releaseGroup(base)
        val (title, year) = titleAndYear(base)
        var tagText = trimmed
        if (title != null && title.length > 4) {
            tagText = titlePattern(title).replaceFirst(tagText, "").trim().replace(WHITESPACE, ".").trim('.')
        }
        val langText = if (group.equals("ind", ignoreCase = true)) tagText.replaceFirst(Regex("ind", RegexOption.IGNORE_CASE), "") else tagText
        val (seasons, episodes) = seasonsAndEpisodes(base)
        return ReleaseInfo(
            title = title,
            year = year,
            resolution = firstTag(tagText, RESOLUTIONS),
            quality = firstTag(tagText, QUALITIES),
            encode = firstTag(tagText, ENCODES),
            visualTags = allTags(tagText, VISUAL_TAGS),
            audioTags = allTags(tagText, AUDIO_TAGS),
            audioChannels = allTags(tagText, AUDIO_CHANNELS),
            languages = allTags(langText, LANGUAGES),
            releaseGroup = group,
            seasons = seasons,
            episodes = episodes,
            editions = EDITIONS.filter { (_, regex) -> regex.containsMatchIn(tagText) }.map { it.first },
            network = NETWORKS.firstOrNull { (_, regex) -> regex.containsMatchIn(tagText) }?.first,
            extension = extension,
            repack = REPACK.containsMatchIn(tagText),
            proper = PROPER.containsMatchIn(tagText),
            uncensored = UNCENSORED.containsMatchIn(tagText),
            unrated = UNRATED.containsMatchIn(tagText),
            regraded = REGRADED.containsMatchIn(tagText),
        )
    }

    /**
     * The title (words before the first year / episode marker / technical tag, separators turned into spaces) and the
     * release year. A year at the very start belongs to the title (`2012.2009.1080p` is the film *2012*).
     */
    internal fun titleAndYear(base: String): Pair<String?, String?> {
        val withoutLeadingTag = LEADING_BRACKET.replaceFirst(base, "")
        val spaced = withoutLeadingTag.replace(SEPARATORS, " ")
        val year = YEAR.findAll(spaced).firstOrNull { it.range.first > 0 }
        val stops = listOfNotNull(
            year?.range?.first?.let { start -> if (start > 0 && spaced[start - 1] == '(') start - 1 else start },
            TITLE_STOP.find(spaced)?.range?.first?.takeIf { it > 0 },
            ANIME_EPISODE.find(spaced)?.range?.first?.takeIf { it > 0 },
        )
        val end = stops.minOrNull() ?: spaced.length
        val title = spaced.substring(0, end).replace(TRAILING_JUNK, "").replace(WHITESPACE, " ").trim()
        return title.ifEmpty { null } to year?.value
    }

    internal fun seasonsAndEpisodes(base: String): Pair<List<Int>, List<Int>> {
        SXE.find(base)?.let { m ->
            val season = m.groupValues[1].toInt()
            val first = m.groupValues[2].toInt()
            val last = m.groupValues[3].toIntOrNull() ?: first
            return listOf(season) to (first..maxOf(first, last)).toList()
        }
        CROSS.find(base)?.let { m -> return listOf(m.groupValues[1].toInt()) to listOf(m.groupValues[2].toInt()) }
        SEASON_RANGE.find(base)?.let { m ->
            val from = m.groupValues[1].toInt()
            val to = m.groupValues[2].toInt()
            return (from..maxOf(from, to)).toList() to emptyList()
        }
        SEASON.find(base)?.let { m -> return listOf((m.groupValues[1].ifEmpty { m.groupValues[2] }).toInt()) to emptyList() }
        ANIME_EPISODE.find(base)?.let { m -> return emptyList<Int>() to listOf(m.groupValues[1].toInt()) }
        return emptyList<Int>() to emptyList()
    }

    /** `…-GROUP` at the end, else a leading `[Group]` (anime fansubs). Codec / source suffixes are not groups. */
    internal fun releaseGroup(base: String): String? {
        val stripped = TRAILING_BRACKETS.replace(base, "")
        val suffix = GROUP_SUFFIX.find(stripped)?.groupValues?.get(1)?.takeIf { it.lowercase() !in NOT_GROUPS }
        return suffix ?: LEADING_BRACKET.find(base)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun titlePattern(title: String): Regex =
        Regex(Regex.escape(title).replace(" ", "\\E[._ ]\\Q"), RegexOption.IGNORE_CASE)

    private fun token(pattern: String) = Regex("(?<![A-Za-z0-9])(?:$pattern)(?![A-Za-z0-9])", RegexOption.IGNORE_CASE)

    private val EXTENSION = Regex("\\.(mkv|mp4|avi|m4v|ts|m2ts|webm|mov|wmv|iso|mpg|flv)$", RegexOption.IGNORE_CASE)
    private val WHITESPACE = Regex("\\s+")
    private val SEPARATORS = Regex("[._]")
    private val LEADING_BRACKET = Regex("^\\s*\\[([^\\]]*)\\]\\s*")
    private val TRAILING_BRACKETS = Regex("(\\s*\\[[^\\]]*\\])+\\s*$")
    private val TRAILING_JUNK = Regex("[\\s\\-(\\[]+$")
    private val YEAR = Regex("(?<![0-9])(19[0-9]{2}|20[0-9]{2})(?![0-9])")
    private val TITLE_STOP = token(
        "S[0-9]{1,2}(?:E[0-9]{1,3})?|[0-9]{1,2}x[0-9]{2,3}|Season [0-9]{1,2}|(?:[0-9]{3,4}[pi])|4k|uhd|blu ?ray|bdrip|brrip|" +
            "remux|web(?:[ -]?dl|rip)?|hdtv|dvd(?:rip)?|hdrip|x26[45]|h ?26[45]|hevc|avc|xvid|complete|multi|repack|proper|" +
            "extended|unrated|imax|hdr(?:10)?|dv|atmos|truehd|dts|aac|ddp?(?:5 1)?",
    )
    private val SXE = Regex("(?<![A-Za-z0-9])S([0-9]{1,2})[ ._]?E([0-9]{1,3})(?:[ ._-]?E?([0-9]{1,3}))?(?![0-9])", RegexOption.IGNORE_CASE)
    private val CROSS = Regex("(?<![A-Za-z0-9])([0-9]{1,2})x([0-9]{2,3})(?![0-9])", RegexOption.IGNORE_CASE)
    private val SEASON_RANGE = Regex("(?<![A-Za-z0-9])S([0-9]{1,2})[ ._]?-[ ._]?S?([0-9]{1,2})(?![0-9])", RegexOption.IGNORE_CASE)
    private val SEASON = Regex("(?<![A-Za-z0-9])(?:S([0-9]{1,2})(?![0-9E])|Season[ ._]?([0-9]{1,2}))(?![0-9])", RegexOption.IGNORE_CASE)
    private val ANIME_EPISODE = Regex(" - ([0-9]{1,4})(?:v[0-9])?(?=[ .\\[(]|$)")
    private val GROUP_SUFFIX = Regex("-([A-Za-z0-9][A-Za-z0-9]*)$")
    private val NOT_GROUPS = setOf("dl", "rip", "hd", "x264", "x265", "h264", "h265", "hevc", "avc", "1", "2", "5", "7")
    private val REPACK = token("repack[0-9]?|rerip")
    private val PROPER = token("proper")
    private val UNCENSORED = token("uncensored")
    private val UNRATED = token("unrated")
    private val REGRADED = token("regraded?")

    private val EDITIONS: List<Pair<String, Regex>> = listOf(
        "Extended Edition" to token("extended(?:[ ._-]?(?:cut|edition))?"),
        "Director's Cut" to token("director'?s?[ ._-]?cut"),
        "Theatrical" to token("theatrical(?:[ ._-]?cut)?"),
        "Uncut" to token("uncut"),
        "Remastered" to token("remaster(?:ed)?"),
        "Ultimate Edition" to token("ultimate(?:[ ._-]?(?:cut|edition))"),
        "Collector's Edition" to token("collector'?s?[ ._-]?edition"),
        "Anniversary Edition" to token("(?:[0-9]+th[ ._-]?)?anniversary(?:[ ._-]?edition)?"),
        "Criterion" to token("criterion"),
        "Special Edition" to token("special[ ._-]?edition"),
        "Final Cut" to token("final[ ._-]?cut"),
    )

    private val NETWORKS: List<Pair<String, Regex>> = listOf(
        "Amazon" to token("amzn|amazon"),
        "Netflix" to token("nf|netflix"),
        "Disney+" to token("dsnp|dsny|disney\\+?"),
        "HBO Max" to token("hmax|hbo[ ._-]?max"),
        "Apple TV+" to token("atvp"),
        "Hulu" to token("hulu"),
        "Peacock" to token("pcok"),
        "Paramount+" to token("pmtp"),
        "iTunes" to token("it(?=[ ._-]web)"),
        "Crunchyroll" to token("cr|crunchyroll"),
    )
}
