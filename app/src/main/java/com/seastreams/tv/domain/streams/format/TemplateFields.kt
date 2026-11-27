package com.seastreams.tv.domain.streams.format

import com.seastreams.tv.data.debrid.library.DebridLibrarySearch
import com.seastreams.tv.domain.streams.ContentKind
import com.seastreams.tv.domain.streams.FilterAttribute
import com.seastreams.tv.domain.streams.StreamFilters
import com.seastreams.tv.domain.streams.parse.LanguageTable
import com.seastreams.tv.domain.streams.parse.ParsedStream
import java.util.Locale

/** Everything a template can read about one stream (AIOStreams `ParseValue`). */
data class FormatInput(
    val stream: ParsedStream,
    val addonName: String,
    val addonUrl: String,
    val kind: ContentKind,
    val title: String? = null,
    val year: Int? = null,
    val runtimeMinutes: Int? = null,
    /** The profile's filters: languages and tags list the user's preferred / required / included values first. */
    val filters: StreamFilters = StreamFilters(),
)

/**
 * The template fields (AIOStreams `engine/fields.ts`, `base.ts` `convertStreamToParseValue`). Field names are matched
 * case-insensitively; a field SeaStreams has no data for (debrid service, SeaDex, …) exists but is always absent, so
 * shared AIOStreams templates still render.
 */
object TemplateFields {

    private val REGISTRY: Map<String, List<String>> = mapOf(
        "config" to listOf("addonName"),
        "stream" to listOf(
            "filename", "folderName", "size", "bitrate", "folderSize", "library", "quality", "resolution", "subbed", "dubbed",
            "languages", "uLanguages", "subtitles", "uSubtitles", "languageEmojis", "uLanguageEmojis", "subtitleEmojis",
            "uSubtitleEmojis", "languageCodes", "uLanguageCodes", "subtitleCodes", "uSubtitleCodes", "smallLanguageCodes",
            "uSmallLanguageCodes", "smallSubtitleCodes", "uSmallSubtitleCodes", "visualTags", "audioTags", "releaseGroup",
            "regexMatched", "encode", "audioChannels", "edition", "editions", "remastered", "regraded", "repack", "proper",
            "uncensored", "unrated", "upscaled", "network", "site", "container", "extension", "indexer", "year", "title",
            "country", "episodeTitle", "date", "seasons", "season", "formattedSeasons", "episodes", "episode",
            "formattedEpisodes", "seasonEpisode", "seasonPack", "seeders", "private", "freeleech", "age", "ageHours",
            "duration", "infoHash", "type", "message", "proxied", "seadex", "seadexBest", "seScore", "nSeScore",
            "seMatched", "rseMatched", "preloading", "idMatched", "failovers",
        ),
        "metadata" to listOf("queryType", "type", "isAnime", "title", "year", "runtime", "genres", "originalLanguage", "season", "episode"),
        "user" to listOf(
            "languages", "subtitles", "resolutions", "qualities", "visualTags", "audioTags", "audioChannels", "encodes",
            "streamTypes", "releaseGroups", "keywords",
        ),
        "service" to listOf("id", "shortName", "name", "cached"),
        "addon" to listOf("name", "presetId", "manifestUrl"),
    )

    private val CANONICAL: Map<String, Pair<String, String>> = REGISTRY.flatMap { (section, properties) ->
        properties.map { "$section.$it".lowercase(Locale.ROOT) to (section to it) }
    }.toMap()

    /** The canonical `section` / `property` spelling, or `null` for an unknown field. */
    fun canonical(section: String, property: String): Pair<String, String>? =
        CANONICAL["$section.$property".lowercase(Locale.ROOT)]

    /** Every field's value for [input]: `null` = absent; the sections hold every registry key. */
    fun values(input: FormatInput): Map<String, Map<String, Any?>> {
        val stream = input.stream
        val release = stream.release
        val f = input.filters
        val languages = byPreference(stream.languages, userValues(f, FilterAttribute.LANGUAGE))
        val subtitles = byPreference(stream.subtitles, userValues(f, FilterAttribute.SUBTITLE).ifEmpty { userValues(f, FilterAttribute.LANGUAGE) })
        val userLanguages = languages.filter { it in userValues(f, FilterAttribute.LANGUAGE) }
        val userSubtitles = subtitles.filter { it in userValues(f, FilterAttribute.SUBTITLE) }
        val streamValues = HashMap<String, Any?>()
        REGISTRY.getValue("stream").forEach { streamValues[it] = null }
        streamValues.putAll(
            mapOf(
                "filename" to stream.filename?.ifEmpty { null },
                "size" to stream.sizeBytes?.takeIf { it > 0 },
                "bitrate" to stream.bitrate,
                // AIOStreams' "in your library": the built-in Debrid source lists only the user's own files.
                "library" to (input.addonUrl == DebridLibrarySearch.DEBRID_ADDON_URL),
                "quality" to release.quality,
                "resolution" to release.resolution,
                "subbed" to stream.subtitles.isNotEmpty(),
                "dubbed" to ("Dubbed" in stream.languages),
                "languages" to languages,
                "uLanguages" to userLanguages,
                "subtitles" to subtitles,
                "uSubtitles" to userSubtitles,
                "languageEmojis" to emojis(languages),
                "uLanguageEmojis" to emojis(userLanguages),
                "subtitleEmojis" to emojis(subtitles),
                "uSubtitleEmojis" to emojis(userSubtitles),
                "languageCodes" to codes(languages),
                "uLanguageCodes" to codes(userLanguages),
                "subtitleCodes" to codes(subtitles),
                "uSubtitleCodes" to codes(userSubtitles),
                "smallLanguageCodes" to smallCodes(languages),
                "uSmallLanguageCodes" to smallCodes(userLanguages),
                "smallSubtitleCodes" to smallCodes(subtitles),
                "uSmallSubtitleCodes" to smallCodes(userSubtitles),
                "visualTags" to byPreference(release.visualTags, userValues(f, FilterAttribute.VISUAL_TAG)),
                "audioTags" to byPreference(release.audioTags, userValues(f, FilterAttribute.AUDIO_TAG)),
                "audioChannels" to byPreference(release.audioChannels, userValues(f, FilterAttribute.AUDIO_CHANNEL)),
                "releaseGroup" to release.releaseGroup,
                "regexMatched" to stream.regexMatched,
                "encode" to release.encode,
                "edition" to release.editions.firstOrNull(),
                "editions" to release.editions.ifEmpty { null },
                "regraded" to release.regraded,
                "repack" to release.repack,
                "proper" to release.proper,
                "uncensored" to release.uncensored,
                "unrated" to release.unrated,
                "upscaled" to ("Upscaled" in release.visualTags),
                "network" to release.network,
                "container" to release.extension,
                "extension" to release.extension,
                "indexer" to stream.indexer,
                "year" to release.year,
                "title" to release.title,
                "seasons" to release.seasons.map(Int::toString).ifEmpty { null },
                "season" to release.seasons.firstOrNull()?.takeIf { it != 0 }?.toLong(),
                "formattedSeasons" to formatted("S", release.seasons),
                "episodes" to release.episodes.map(Int::toString).ifEmpty { null },
                "episode" to release.episodes.firstOrNull()?.takeIf { it != 0 }?.toLong(),
                "formattedEpisodes" to formatted("E", release.episodes),
                "seasonEpisode" to listOfNotNull(formatted("S", release.seasons), formatted("E", release.episodes)),
                "seasonPack" to release.seasonPack,
                "seeders" to stream.seeders?.toLong(),
                "private" to false,
                "age" to stream.ageHours?.takeIf { it > 0 }?.let(::formatHours),
                "ageHours" to stream.ageHours?.takeIf { it > 0 },
                "duration" to stream.durationMs?.takeIf { it > 0 },
                "type" to stream.type.id,
                "proxied" to false,
                "seadex" to false,
                "seadexBest" to false,
                "preloading" to false,
                "failovers" to 0L,
            ),
        )
        return mapOf(
            "stream" to streamValues,
            "config" to mapOf("addonName" to APP_NAME),
            "service" to mapOf("id" to null, "shortName" to null, "name" to null, "cached" to null),
            "addon" to mapOf("name" to input.addonName, "presetId" to null, "manifestUrl" to input.addonUrl),
            "metadata" to mapOf(
                "queryType" to queryType(input.kind),
                "type" to if (input.kind == ContentKind.MOVIE) "movie" else "series",
                "isAnime" to (input.kind == ContentKind.ANIME),
                "title" to input.title,
                "year" to input.year?.toLong(),
                "runtime" to input.runtimeMinutes?.toLong(),
                "genres" to null,
                "originalLanguage" to null,
                "season" to release.seasons.firstOrNull()?.toLong(),
                "episode" to release.episodes.firstOrNull()?.toLong(),
            ),
            "user" to FilterAttribute.entries.filter { it != FilterAttribute.KEYWORD }.associate { it.field to userValues(f, it) } +
                ("keywords" to userValues(f, FilterAttribute.KEYWORD)),
        )
    }

    /** Hours under a day print as `5h`, longer ages in whole days (`10d`). */
    internal fun formatHours(hours: Double): String =
        if (hours < HOURS_PER_DAY) "${hours.toLong()}h" else "${(hours / HOURS_PER_DAY).toLong()}d"

    /** `S01` / `S01-03`, `E05` / `E05-07`. */
    private fun formatted(prefix: String, numbers: List<Int>): String? = when (numbers.size) {
        0 -> null
        1 -> prefix + pad(numbers[0])
        else -> prefix + pad(numbers.first()) + "-" + pad(numbers.last())
    }

    private fun pad(number: Int): String = number.toString().padStart(2, '0')

    /** The user's preferred, required and included values of [attribute], in that order. */
    private fun userValues(filters: StreamFilters, attribute: FilterAttribute): List<String> {
        val filter = filters.attribute(attribute)
        return (filter.preferred + filter.required + filter.included).distinct()
    }

    /** Values the user listed first, in their order; the rest keep the release order. */
    private fun byPreference(values: List<String>, preferences: List<String>): List<String> {
        if (preferences.isEmpty()) return values
        return values.withIndex().sortedWith(compareBy({ preferences.indexOf(it.value).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it.index }))
            .map { it.value }
    }

    private fun emojis(languages: List<String>): List<String> = languages.mapNotNull(LanguageTable::emoji).distinct()

    private fun codes(languages: List<String>): List<String> =
        languages.map { LanguageTable.code(it) ?: it.uppercase(Locale.ROOT) }.distinct()

    private fun smallCodes(languages: List<String>): List<String> =
        languages.mapNotNull(LanguageTable::code).map(TemplateValueFormat::smallCaps).distinct()

    private fun queryType(kind: ContentKind): String = when (kind) {
        ContentKind.MOVIE -> "movie"
        ContentKind.SERIES -> "series"
        ContentKind.ANIME -> "anime.series"
    }

    private const val APP_NAME = "seastreams"
    private const val HOURS_PER_DAY = 24.0
}
