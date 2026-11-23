package com.seastreams.tv.domain.streams

import com.seastreams.tv.data.usenet.newznab.UsenetStreamSearch
import com.seastreams.tv.domain.model.StreamSource
import com.seastreams.tv.domain.streams.filter.KeywordMatcher
import com.seastreams.tv.domain.streams.filter.ResultLimiter
import com.seastreams.tv.domain.streams.filter.StreamCandidate
import com.seastreams.tv.domain.streams.filter.StreamDeduplicator
import com.seastreams.tv.domain.streams.filter.StreamFilterEngine
import com.seastreams.tv.domain.streams.format.FormatInput
import com.seastreams.tv.domain.streams.format.PredefinedFormatters
import com.seastreams.tv.domain.streams.format.StreamFormatter
import com.seastreams.tv.domain.streams.parse.StreamFacts
import com.seastreams.tv.domain.streams.sort.StreamSorter

/** What is being resolved, as the pipeline needs it. [addonOrder] is the profile's addon URLs, in order. */
data class StreamContext(
    val kind: ContentKind,
    val runtimeMinutes: Int? = null,
    val title: String? = null,
    val year: Int? = null,
    val addonOrder: List<String> = emptyList(),
)

/**
 * PRD §13 / ARCHITECTURE §11: parse → filter → deduplicate → sort → result limits → format. With default settings the
 * list comes back untouched. Each stream keeps its raw `label` / `description`; [StreamSource.parsed] and
 * [StreamSource.display] carry the results.
 */
object StreamPipeline {

    /**
     * Stages switched off by their master toggle are skipped ([StreamProcessingSettings.effective]); with nothing left
     * to do the list comes back untouched, without parsing.
     */
    fun process(streams: List<StreamSource>, settings: StreamProcessingSettings, context: StreamContext, nowMillis: Long): List<StreamSource> {
        val active = settings.effective()
        if (active.isDefault || streams.isEmpty()) return streams
        val durationMs = context.runtimeMinutes?.takeIf { it > 0 }?.let { it * MILLIS_PER_MINUTE }
        val parsed = streams.map { StreamCandidate(it, StreamFacts.parse(it, durationMs, nowMillis, UsenetStreamSearch.USENET_ADDON_URL)) }
        val filters = active.filters
        val filtering = filters != StreamFilters()
        val filtered = if (filtering) StreamFilterEngine(filters, context.kind).filter(parsed) else parsed
        val annotated = if (filtering) annotatePreferred(filtered, filters) else filtered
        val unique = if (filtering) StreamDeduplicator.deduplicate(annotated, filters.dedup) else annotated
        val sorted = if (active.sort != StreamSort()) StreamSorter(active.sort, filters, context.addonOrder).sort(unique, context.kind) else unique
        val limited = if (filtering) ResultLimiter.limit(sorted, filters.limits) else sorted
        val choice = active.formatter
        val formatter = PredefinedFormatters.templates(choice.id, choice.customName, choice.customDescription)?.let(::StreamFormatter)
        return limited.map { candidate ->
            val display = formatter?.let { format(it, candidate, context, filters) }
            candidate.source.copy(parsed = candidate.parsed, display = display)
        }
    }

    /** Marks the first *Preferred* regex (by list position) and whether a *Preferred* keyword matched, for sorting. */
    internal fun annotatePreferred(candidates: List<StreamCandidate>, filters: StreamFilters): List<StreamCandidate> {
        val regexes = filters.regex.preferred.mapNotNull { named -> KeywordMatcher.compile(named.pattern)?.let { named to it } }
        val keywords = KeywordMatcher.keywords(filters.keywords.preferred)
        if (regexes.isEmpty() && keywords == null) return candidates
        return candidates.map { candidate ->
            val stream = candidate.parsed
            val hit = regexes.withIndex().firstOrNull { (_, entry) -> entry.second.matches(stream) }
            candidate.copy(
                parsed = stream.copy(
                    regexMatched = hit?.value?.first?.let { it.name.ifBlank { it.pattern } },
                    regexIndex = hit?.index,
                    keywordMatched = keywords?.matches(stream) == true,
                ),
            )
        }
    }

    /** A template that fails on one stream's data leaves that stream showing the addon's own text. */
    private fun format(formatter: StreamFormatter, candidate: StreamCandidate, context: StreamContext, filters: StreamFilters) = try {
        formatter.format(
            FormatInput(
                stream = candidate.parsed,
                addonName = candidate.source.addonName,
                addonUrl = candidate.source.addonUrl,
                kind = context.kind,
                title = context.title,
                year = context.year,
                runtimeMinutes = context.runtimeMinutes,
                filters = filters,
            ),
        )
    } catch (e: RuntimeException) {
        null
    }

    private const val MILLIS_PER_MINUTE = 60_000L
}
