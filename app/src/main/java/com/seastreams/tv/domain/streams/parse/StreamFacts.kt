package com.seastreams.tv.domain.streams.parse

import com.seastreams.tv.data.debrid.library.DebridLibrarySearch
import com.seastreams.tv.data.debrid.stream.DebridStreamRegistry
import com.seastreams.tv.domain.model.StreamSource
import com.seastreams.tv.usenet.session.UsenetStreamRegistry
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Builds a [ParsedStream] from an addon stream's raw text and hints (AIOStreams `StreamParser`): the file name, size,
 * seeders, age and indexer it mentions, its delivery type, and the release attributes of the file name.
 */
object StreamFacts {

    /** [durationMs] is the TMDB runtime used for the bitrate estimate; [nowMillis] dates Newznab publish times. */
    fun parse(stream: StreamSource, durationMs: Long?, nowMillis: Long, usenetAddonUrl: String): ParsedStream {
        val filename = stream.hints.filename ?: filenameFrom(stream.description.ifBlank { stream.label })
        val release = filename?.let(ReleaseParser::parse) ?: ReleaseInfo()
        val labelRelease = ReleaseParser.parse(stream.label)
        val resolved = if (release.resolution == null && labelRelease.resolution != null) release.copy(resolution = labelRelease.resolution) else release
        val text = filename?.let { stream.description.replace(it, "") } ?: stream.description
        val size = stream.hints.sizeBytes?.takeIf { it > 0 } ?: sizeIn(text) ?: sizeIn(stream.label)
        val duration = durationMs?.takeIf { it > 0 }
        val flagLanguages = LanguageTable.fromFlags(stream.description) + LanguageTable.fromFlags(stream.label)
        return ParsedStream(
            release = resolved,
            filename = filename,
            type = kindOf(stream, usenetAddonUrl),
            sizeBytes = size,
            bitrate = if (size != null && duration != null) (size * BITS_PER_BYTE * MILLIS_PER_SECOND / duration.toDouble()).roundToLong() else null,
            durationMs = duration,
            seeders = SEEDERS.find(stream.description)?.groupValues?.get(1)?.toIntOrNull(),
            ageHours = stream.hints.publishedMillis?.let { ((nowMillis - it).coerceAtLeast(0) / MILLIS_PER_HOUR.toDouble()) } ?: ageIn(stream.description),
            indexer = stream.hints.indexer ?: indexerIn(stream.description),
            languages = (resolved.languages + flagLanguages).distinct(),
        )
    }

    /**
     * The release name among the first five description lines: the first one that names a year or a season/episode,
     * else the first line, without leading emoji and any `Label:` prefix (AIOStreams `getFilename`).
     */
    internal fun filenameFrom(description: String): String? {
        val lines = description.lines().filter { it.isNotBlank() }.take(MAX_FILENAME_LINES)
        val line = lines.firstOrNull { candidate ->
            val parsed = ReleaseParser.parse(clean(candidate))
            parsed.year != null || parsed.seasons.isNotEmpty() || parsed.episodes.isNotEmpty()
        } ?: lines.firstOrNull() ?: return null
        return clean(line).ifEmpty { null }
    }

    private fun clean(line: String): String {
        val trimmed = line.trim()
        var start = 0
        while (start < trimmed.length) {
            val cp = trimmed.codePointAt(start)
            if (Character.isLetterOrDigit(cp) || cp == '['.code || cp == '('.code) break
            start += Character.charCount(cp)
        }
        return trimmed.substring(start).replaceFirst(LABEL_PREFIX, "").trim()
    }

    /** `14.2 GB`, `700 MB`, `1.5 TiB` in [text], in bytes (1024-based, as AIOStreams parses addon sizes). */
    internal fun sizeIn(text: String): Long? {
        val match = SIZE.find(text) ?: return null
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        val power = when (match.groupValues[3].uppercase(Locale.ROOT).first()) {
            'K' -> 1
            'M' -> 2
            'G' -> 3
            else -> 4
        }
        return (value * Math.pow(KIBI, power.toDouble())).roundToLong().takeIf { it > 0 }
    }

    /** `📅 3d` / `📅 5h` / `12 days old` in [text], in hours. */
    internal fun ageIn(text: String): Double? {
        AGE.find(text)?.let { m ->
            val value = m.groupValues[1].toDoubleOrNull() ?: return null
            return value * when (m.groupValues[2].lowercase(Locale.ROOT)) {
                "h" -> 1.0
                "d" -> HOURS_PER_DAY
                "w" -> HOURS_PER_DAY * DAYS_PER_WEEK
                "m" -> HOURS_PER_DAY * DAYS_PER_MONTH
                else -> HOURS_PER_DAY * DAYS_PER_YEAR
            }
        }
        return DAYS_OLD.find(text)?.groupValues?.get(1)?.toDoubleOrNull()?.times(HOURS_PER_DAY)
    }

    /** The text after an indexer marker emoji (`🌐 1337x`, `⚙️ RARBG`), up to the next emoji or line end. */
    internal fun indexerIn(text: String): String? {
        val match = INDEXER.find(text) ?: return null
        val rest = match.groupValues[1]
        val end = rest.indices.firstOrNull { i -> rest[i].isSurrogate() || rest[i].code in SYMBOL_RANGE } ?: rest.length
        return rest.substring(0, end).trim().ifEmpty { null }
    }

    internal fun kindOf(stream: StreamSource, usenetAddonUrl: String): StreamKind = when {
        stream.addonUrl == usenetAddonUrl || UsenetStreamRegistry.isUsenet(stream.url) -> StreamKind.USENET
        // The built-in Debrid library (PRD §14): its type comes from where it was found, not from its text.
        stream.addonUrl == DebridLibrarySearch.DEBRID_ADDON_URL || DebridStreamRegistry.isDebrid(stream.url) -> StreamKind.DEBRID
        HLS.containsMatchIn(stream.url.substringBefore('#').substringBefore('?')) -> StreamKind.LIVE
        DEBRID.containsMatchIn(stream.label) || DEBRID.containsMatchIn(stream.description) -> StreamKind.DEBRID
        else -> StreamKind.HTTP
    }

    private const val MAX_FILENAME_LINES = 5
    private const val BITS_PER_BYTE = 8
    private const val MILLIS_PER_SECOND = 1_000
    private const val MILLIS_PER_HOUR = 3_600_000L
    private const val KIBI = 1024.0
    private const val HOURS_PER_DAY = 24.0
    private const val DAYS_PER_WEEK = 7
    private const val DAYS_PER_MONTH = 30
    private const val DAYS_PER_YEAR = 365
    private val SYMBOL_RANGE = 0x2190..0x2BFF
    private val LABEL_PREFIX = Regex("^[^\\s:]+:\\s*")
    private val SIZE = Regex("([0-9]+(\\.[0-9]+)?)\\s?(KiB|MiB|GiB|TiB|KB|MB|GB|TB)", RegexOption.IGNORE_CASE)
    private val SEEDERS = Regex("[👥👤]\\s*([0-9]+)")
    private val AGE = Regex("📅\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(h|d|w|m|y)(?![A-Za-z])", RegexOption.IGNORE_CASE)
    private val DAYS_OLD = Regex("([0-9]+)\\s+days?\\s+old", RegexOption.IGNORE_CASE)
    private val INDEXER = Regex("(?:🌐|⚙️|⚙|🔗|🔎|🔍|☁️|☁)[^\\S\\r\\n]*([^\\r\\n]*)")
    private val HLS = Regex("\\.m3u8?$", RegexOption.IGNORE_CASE)
    private val DEBRID = Regex(
        "\\[(?:RD|AD|PM|TB|DL|ED|OC|PK|DR|EN|PP|ST)\\+?(?:\\]|\\s)|\\b(?:RD|AD|PM|TB)\\+|Real-?Debrid|AllDebrid|Premiumize|TorBox|Debrid-?Link|EasyDebrid|Offcloud|PikPak",
        RegexOption.IGNORE_CASE,
    )
}
