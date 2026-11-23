package com.seastreams.tv.domain.streams.parse

/** What a release name says about itself (AIOStreams `ParsedFile`). Empty lists / `null` = not stated. */
data class ReleaseInfo(
    val title: String? = null,
    val year: String? = null,
    val resolution: String? = null,
    val quality: String? = null,
    val encode: String? = null,
    val visualTags: List<String> = emptyList(),
    val audioTags: List<String> = emptyList(),
    val audioChannels: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val releaseGroup: String? = null,
    val seasons: List<Int> = emptyList(),
    val episodes: List<Int> = emptyList(),
    val editions: List<String> = emptyList(),
    val network: String? = null,
    val extension: String? = null,
    val repack: Boolean = false,
    val proper: Boolean = false,
    val uncensored: Boolean = false,
    val unrated: Boolean = false,
    val regraded: Boolean = false,
) {
    /** A season pack names seasons but no episode. */
    val seasonPack: Boolean get() = seasons.isNotEmpty() && episodes.isEmpty()
}

/** How a stream is delivered, the `Stream type` filter / sort attribute. */
enum class StreamKind(val id: String, val label: String) {
    DEBRID("debrid", "Debrid"),
    USENET("usenet", "Usenet"),
    HTTP("http", "HTTP"),
    LIVE("live", "Live"),
}

/**
 * One stream's parsed attributes (AIOStreams `ParsedStream`), built by [ReleaseParser.parseStream] from the addon's
 * raw text and hints. Missing facts stay `null`: filters skip them and sorting treats them as `0` / not preferred.
 */
data class ParsedStream(
    val release: ReleaseInfo,
    /** The release / file name the attributes were read from. */
    val filename: String?,
    val type: StreamKind,
    val sizeBytes: Long? = null,
    /** Estimated bits per second: size ÷ TMDB runtime. */
    val bitrate: Long? = null,
    /** TMDB runtime of the movie or episode, in milliseconds. */
    val durationMs: Long? = null,
    val seeders: Int? = null,
    val ageHours: Double? = null,
    /** The indexer / tracker / source the addon named. */
    val indexer: String? = null,
    /** Release languages plus any the addon's flag emojis add. */
    val languages: List<String> = release.languages,
    val subtitles: List<String> = emptyList(),
    /** Name of the first `Preferred` regex that matched (set by the pipeline), with its list position. */
    val regexMatched: String? = null,
    val regexIndex: Int? = null,
    /** Whether a `Preferred` keyword matched (set by the pipeline). */
    val keywordMatched: Boolean = false,
) {
    /** The strings regex and keyword filters are tested against (AIOStreams `testRegexes`). */
    val matchTexts: List<String> get() = listOfNotNull(filename, release.releaseGroup, indexer)
}
