package com.seastreams.tv.domain.streams.filter

import com.seastreams.tv.domain.model.StreamSource
import com.seastreams.tv.domain.streams.AttributeFilter
import com.seastreams.tv.domain.streams.ContentKind
import com.seastreams.tv.domain.streams.FilterAttribute
import com.seastreams.tv.domain.streams.RangeRule
import com.seastreams.tv.domain.streams.StreamFilters
import com.seastreams.tv.domain.streams.ValueRange
import com.seastreams.tv.domain.streams.parse.ParsedStream
import com.seastreams.tv.domain.streams.parse.ReleaseRegexTables.UNKNOWN
import com.seastreams.tv.domain.streams.parse.withFakeVisualTags

/** A stream travelling through the pipeline with its parsed attributes. */
data class StreamCandidate(val source: StreamSource, val parsed: ParsedStream)

/**
 * The filter stage (AIOStreams `filterer.ts`, `aio-filters.png`):
 * - A stream matching any *Included* value, regex, keyword, seeder or age range is kept whatever else applies.
 * - Otherwise every *Excluded* / *Required* list must pass. A missing attribute counts as `Unknown`, so `Unknown` can
 *   be excluded or required like any value. Multi-valued tags are excluded when any tag is excluded and pass a
 *   required list when any tag is in it.
 * - Languages and subtitles are excluded only when *every* language of the stream is excluded, and required when any
 *   one is in the list.
 * - Keywords and regexes are tested against the file name, release group and indexer; a regex with the `n` flag is
 *   negated ([KeywordMatcher]).
 * - Seeder and age ranges, size and bitrate limits ([SizeBitrateRules]) only judge streams that state the value.
 */
class StreamFilterEngine(private val filters: StreamFilters, private val kind: ContentKind) {

    private val requiredRegex = KeywordMatcher.compileAll(filters.regex.required)
    private val excludedRegex = KeywordMatcher.compileAll(filters.regex.excluded)
    private val includedRegex = KeywordMatcher.compileAll(filters.regex.included)
    private val requiredKeywords = KeywordMatcher.keywords(filters.keywords.required)
    private val excludedKeywords = KeywordMatcher.keywords(filters.keywords.excluded)
    private val includedKeywords = KeywordMatcher.keywords(filters.keywords.included)

    fun filter(candidates: List<StreamCandidate>): List<StreamCandidate> = candidates.filter { keep(it.parsed) }

    fun keep(stream: ParsedStream): Boolean = isIncluded(stream) || passes(stream)

    /** Whether an *Included* rule keeps [stream] regardless of every other filter. */
    fun isIncluded(stream: ParsedStream): Boolean =
        LIST_ATTRIBUTES.any { attribute -> matchesAny(values(stream, attribute), filters.attribute(attribute).included) } ||
            includedRegex.any { it.matches(stream) } ||
            includedKeywords?.matches(stream) == true ||
            inRange(stream.seeders?.toLong(), filters.seeders.included) ||
            inRange(stream.ageHours?.toLong(), filters.ageHours.included)

    /** Whether [stream] passes every *Required* / *Excluded* list, range and limit. */
    fun passes(stream: ParsedStream): Boolean =
        LIST_ATTRIBUTES.all { attribute -> passesList(attribute, values(stream, attribute), filters.attribute(attribute)) } &&
            excludedRegex.none { it.matches(stream) } &&
            (requiredRegex.isEmpty() || requiredRegex.any { it.matches(stream) }) &&
            excludedKeywords?.matches(stream) != true &&
            requiredKeywords?.matches(stream) != false &&
            passesRange(stream.seeders?.toLong(), filters.seeders) &&
            passesRange(stream.ageHours?.toLong(), filters.ageHours) &&
            SizeBitrateRules.allows(stream, kind, filters.size, filters.bitrate)

    private fun passesList(attribute: FilterAttribute, values: List<String>, filter: AttributeFilter): Boolean {
        val excluded = if (attribute in LANGUAGE_LIKE) {
            filter.excluded.isNotEmpty() && values.all { value -> filter.excluded.any { it.equals(value, ignoreCase = true) } }
        } else {
            matchesAny(values, filter.excluded)
        }
        if (excluded) return false
        return filter.required.isEmpty() || matchesAny(values, filter.required)
    }

    private fun passesRange(value: Long?, rule: RangeRule): Boolean {
        if (value == null) return true
        if (!rule.excluded.isOpen && value in rule.excluded) return false
        return rule.required.isOpen || value in rule.required
    }

    private fun inRange(value: Long?, range: ValueRange): Boolean = value != null && !range.isOpen && value in range

    companion object {
        /** The attributes checked as value lists ([FilterAttribute.KEYWORD] is a pattern, handled separately). */
        private val LIST_ATTRIBUTES = FilterAttribute.entries - FilterAttribute.KEYWORD
        private val LANGUAGE_LIKE = setOf(FilterAttribute.LANGUAGE, FilterAttribute.SUBTITLE)

        /** The stream's values for [attribute]; `[Unknown]` when it states none. */
        fun values(stream: ParsedStream, attribute: FilterAttribute): List<String> {
            val release = stream.release
            val values = when (attribute) {
                FilterAttribute.STREAM_TYPE -> listOf(stream.type.label)
                FilterAttribute.RESOLUTION -> listOfNotNull(release.resolution)
                FilterAttribute.QUALITY -> listOfNotNull(release.quality)
                FilterAttribute.ENCODE -> listOfNotNull(release.encode)
                FilterAttribute.VISUAL_TAG -> withFakeVisualTags(release.visualTags)
                FilterAttribute.AUDIO_TAG -> release.audioTags
                FilterAttribute.AUDIO_CHANNEL -> release.audioChannels
                FilterAttribute.LANGUAGE -> stream.languages
                FilterAttribute.SUBTITLE -> stream.subtitles
                FilterAttribute.RELEASE_GROUP -> listOfNotNull(release.releaseGroup)
                FilterAttribute.KEYWORD -> emptyList()
            }
            return values.ifEmpty { listOf(UNKNOWN) }
        }

        fun matchesAny(values: List<String>, list: List<String>): Boolean =
            list.isNotEmpty() && values.any { value -> list.any { it.equals(value, ignoreCase = true) } }
    }
}
