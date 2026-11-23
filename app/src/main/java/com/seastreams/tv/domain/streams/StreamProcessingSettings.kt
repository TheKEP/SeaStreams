package com.seastreams.tv.domain.streams

import com.seastreams.tv.domain.streams.parse.ReleaseRegexTables
import com.seastreams.tv.domain.streams.parse.StreamKind
import kotlinx.serialization.Serializable

/**
 * One profile's stream processing (PRD §13), stored as three JSON ScopedKeys (`stream_filters`, `stream_sort`,
 * `stream_formatter`). Every default means "no change": no filter, no sorting, the addon's own text.
 */
data class StreamProcessingSettings(
    val filters: StreamFilters = StreamFilters(),
    val sort: StreamSort = StreamSort(),
    val formatter: StreamFormatterChoice = StreamFormatterChoice(),
) {
    val isDefault: Boolean get() = this == StreamProcessingSettings()
}

/**
 * *Required* (drop anything else), *Excluded* (drop), *Included* (keep regardless of every other filter) and
 * *Preferred* (ordered; ranks streams when the matching sort criterion is on) values of one attribute.
 */
@Serializable
data class AttributeFilter(
    val required: List<String> = emptyList(),
    val excluded: List<String> = emptyList(),
    val included: List<String> = emptyList(),
    val preferred: List<String> = emptyList(),
) {
    fun list(kind: FilterList): List<String> = when (kind) {
        FilterList.REQUIRED -> required
        FilterList.EXCLUDED -> excluded
        FilterList.INCLUDED -> included
        FilterList.PREFERRED -> preferred
    }

    fun with(kind: FilterList, values: List<String>): AttributeFilter = when (kind) {
        FilterList.REQUIRED -> copy(required = values)
        FilterList.EXCLUDED -> copy(excluded = values)
        FilterList.INCLUDED -> copy(included = values)
        FilterList.PREFERRED -> copy(preferred = values)
    }

    val isEmpty: Boolean get() = this == AttributeFilter()
}

enum class FilterList(val label: String) {
    REQUIRED("Required"),
    EXCLUDED("Excluded"),
    INCLUDED("Included"),
    PREFERRED("Preferred"),
}

/** An inclusive range; either end may be open. */
@Serializable
data class ValueRange(val min: Long? = null, val max: Long? = null) {
    val isOpen: Boolean get() = min == null && max == null

    operator fun contains(value: Long): Boolean = (min == null || value >= min) && (max == null || value <= max)
}

/** Seeder or age (hours) limits: keep only [required], drop [excluded], keep [included] regardless. */
@Serializable
data class RangeRule(
    val required: ValueRange = ValueRange(),
    val excluded: ValueRange = ValueRange(),
    val included: ValueRange = ValueRange(),
)

/** Size (bytes) or bitrate (bits/s) limits for movies and for series (anime uses the series limits). */
@Serializable
data class MovieSeriesRange(val movies: ValueRange = ValueRange(), val series: ValueRange = ValueRange())

/** A [global] limit plus per-resolution overrides (`2160p` → its own movie/series range). */
@Serializable
data class SizeLimits(
    val global: MovieSeriesRange = MovieSeriesRange(),
    val byResolution: Map<String, MovieSeriesRange> = emptyMap(),
)

/** A regex as typed: `/pattern/flags`, where the extra `n` flag negates it; a bare pattern has no flags. */
@Serializable
data class NamedRegex(val pattern: String, val name: String = "")

@Serializable
data class RegexRules(
    val required: List<String> = emptyList(),
    val excluded: List<String> = emptyList(),
    val included: List<String> = emptyList(),
    val preferred: List<NamedRegex> = emptyList(),
)

/** Caps applied after sorting; `null` = unlimited. Each counts independently. */
@Serializable
data class ResultLimits(
    val global: Int? = null,
    val perResolution: Int? = null,
    val perQuality: Int? = null,
    val perAddon: Int? = null,
)

@Serializable
enum class DedupKey(val label: String) { FILENAME("File name"), URL("Stream URL"), SMART("Smart detect (size + tags)") }

@Serializable
enum class DedupMode(val label: String) { PER_ADDON("Per addon"), SINGLE_RESULT("Across all addons") }

@Serializable
data class DedupSettings(
    val enabled: Boolean = false,
    val keys: List<DedupKey> = listOf(DedupKey.FILENAME, DedupKey.URL),
    val mode: DedupMode = DedupMode.PER_ADDON,
)

/** Every filter group of `aio-filters.png` that SeaStreams supports. */
@Serializable
data class StreamFilters(
    val streamTypes: AttributeFilter = AttributeFilter(),
    val resolutions: AttributeFilter = AttributeFilter(),
    val qualities: AttributeFilter = AttributeFilter(),
    val encodes: AttributeFilter = AttributeFilter(),
    val visualTags: AttributeFilter = AttributeFilter(),
    val audioTags: AttributeFilter = AttributeFilter(),
    val audioChannels: AttributeFilter = AttributeFilter(),
    val languages: AttributeFilter = AttributeFilter(),
    val subtitles: AttributeFilter = AttributeFilter(),
    val releaseGroups: AttributeFilter = AttributeFilter(),
    val keywords: AttributeFilter = AttributeFilter(),
    val regex: RegexRules = RegexRules(),
    val seeders: RangeRule = RangeRule(),
    val ageHours: RangeRule = RangeRule(),
    val size: SizeLimits = SizeLimits(),
    val bitrate: SizeLimits = SizeLimits(),
    val limits: ResultLimits = ResultLimits(),
    val dedup: DedupSettings = DedupSettings(),
) {
    fun attribute(attribute: FilterAttribute): AttributeFilter = when (attribute) {
        FilterAttribute.STREAM_TYPE -> streamTypes
        FilterAttribute.RESOLUTION -> resolutions
        FilterAttribute.QUALITY -> qualities
        FilterAttribute.ENCODE -> encodes
        FilterAttribute.VISUAL_TAG -> visualTags
        FilterAttribute.AUDIO_TAG -> audioTags
        FilterAttribute.AUDIO_CHANNEL -> audioChannels
        FilterAttribute.LANGUAGE -> languages
        FilterAttribute.SUBTITLE -> subtitles
        FilterAttribute.RELEASE_GROUP -> releaseGroups
        FilterAttribute.KEYWORD -> keywords
    }

    fun withAttribute(attribute: FilterAttribute, value: AttributeFilter): StreamFilters = when (attribute) {
        FilterAttribute.STREAM_TYPE -> copy(streamTypes = value)
        FilterAttribute.RESOLUTION -> copy(resolutions = value)
        FilterAttribute.QUALITY -> copy(qualities = value)
        FilterAttribute.ENCODE -> copy(encodes = value)
        FilterAttribute.VISUAL_TAG -> copy(visualTags = value)
        FilterAttribute.AUDIO_TAG -> copy(audioTags = value)
        FilterAttribute.AUDIO_CHANNEL -> copy(audioChannels = value)
        FilterAttribute.LANGUAGE -> copy(languages = value)
        FilterAttribute.SUBTITLE -> copy(subtitles = value)
        FilterAttribute.RELEASE_GROUP -> copy(releaseGroups = value)
        FilterAttribute.KEYWORD -> copy(keywords = value)
    }
}

/** The list-valued filter groups; [options] is `null` for free text (typed in the Web Portal). */
enum class FilterAttribute(val label: String, val options: List<String>?) {
    STREAM_TYPE("Stream type", StreamKindNames),
    RESOLUTION("Resolution", ReleaseRegexTables.RESOLUTION_NAMES),
    QUALITY("Quality", ReleaseRegexTables.QUALITY_NAMES),
    ENCODE("Encode", ReleaseRegexTables.ENCODE_NAMES),
    VISUAL_TAG("Visual tag", ReleaseRegexTables.VISUAL_TAG_NAMES),
    AUDIO_TAG("Audio tag", ReleaseRegexTables.AUDIO_TAG_NAMES),
    AUDIO_CHANNEL("Audio channel", ReleaseRegexTables.AUDIO_CHANNEL_NAMES),
    LANGUAGE("Language", ReleaseRegexTables.LANGUAGE_NAMES),
    SUBTITLE("Subtitle", ReleaseRegexTables.LANGUAGE_NAMES),
    RELEASE_GROUP("Release group", null),
    KEYWORD("Keyword", null),
}

private val StreamKindNames: List<String> get() = StreamKind.entries.map { it.label }

@Serializable
enum class SortKey(val label: String, val description: String) {
    QUALITY("Quality", "Streams in your preferred quality list first"),
    RESOLUTION("Resolution", "Streams in your preferred resolution list first"),
    LANGUAGE("Language", "Streams in your preferred language list first"),
    SUBTITLE("Subtitle", "Streams in your preferred subtitle list first"),
    VISUAL_TAG("Visual tag", "Streams in your preferred visual tag list first"),
    AUDIO_TAG("Audio tag", "Streams in your preferred audio tag list first"),
    AUDIO_CHANNEL("Audio channel", "Streams in your preferred audio channel list first"),
    ENCODE("Encode", "Streams in your preferred encode list first"),
    STREAM_TYPE("Stream type", "Streams in your preferred stream type list first"),
    SIZE("Size", "Larger streams first"),
    BITRATE("Bitrate (estimate)", "Higher bitrate first (size ÷ runtime)"),
    SEEDERS("Seeders", "More seeders first"),
    AGE("Age", "Older releases first"),
    ADDON("Addon", "Your addon order"),
    KEYWORD("Keyword", "Streams matching a preferred keyword first"),
    REGEX("Regex patterns", "Streams matching an earlier preferred regex first"),
    RELEASE_GROUP("Release group", "Streams in your preferred release group list first"),
}

/** `DESC` puts larger values / earlier preferred entries first (AIOStreams' default direction). */
@Serializable
enum class SortDirection(val label: String) { DESC("Descending"), ASC("Ascending") }

@Serializable
data class SortCriterion(val key: SortKey, val direction: SortDirection = SortDirection.DESC)

/** Which list sorts which content. */
enum class SortScope(val label: String) { GLOBAL("Global"), MOVIES("Movies"), SERIES("Series"), ANIME("Anime") }

/** Sort lists; a non-empty Movies / Series / Anime list replaces [global] for that content. Empty = no sorting. */
@Serializable
data class StreamSort(
    val global: List<SortCriterion> = emptyList(),
    val movies: List<SortCriterion> = emptyList(),
    val series: List<SortCriterion> = emptyList(),
    val anime: List<SortCriterion> = emptyList(),
) {
    fun list(scope: SortScope): List<SortCriterion> = when (scope) {
        SortScope.GLOBAL -> global
        SortScope.MOVIES -> movies
        SortScope.SERIES -> series
        SortScope.ANIME -> anime
    }

    fun with(scope: SortScope, criteria: List<SortCriterion>): StreamSort = when (scope) {
        SortScope.GLOBAL -> copy(global = criteria)
        SortScope.MOVIES -> copy(movies = criteria)
        SortScope.SERIES -> copy(series = criteria)
        SortScope.ANIME -> copy(anime = criteria)
    }

    /** The list that applies to [kind]: its own when non-empty, else [global]. */
    fun effective(kind: ContentKind): List<SortCriterion> = when (kind) {
        ContentKind.MOVIE -> movies
        ContentKind.SERIES -> series
        ContentKind.ANIME -> anime
    }.ifEmpty { global }
}

/** What is being played, for type-specific sort lists and size limits. */
enum class ContentKind { MOVIE, SERIES, ANIME }

/** The chosen formatter; [ORIGINAL] keeps the addon's text. Custom templates are typed in the Web Portal. */
@Serializable
data class StreamFormatterChoice(
    val id: FormatterId = FormatterId.ORIGINAL,
    val customName: String? = null,
    val customDescription: String? = null,
)

@Serializable
enum class FormatterId(val label: String) {
    ORIGINAL("Original (addon text)"),
    TORRENTIO("Torrentio"),
    GDRIVE("GDrive"),
    LIGHT_GDRIVE("Light GDrive"),
    MINIMALISTIC_GDRIVE("Minimalistic GDrive"),
    PRISM("Prism"),
    CUSTOM("Custom"),
}
