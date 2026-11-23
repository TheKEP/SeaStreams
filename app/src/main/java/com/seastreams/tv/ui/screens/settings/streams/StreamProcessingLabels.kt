package com.seastreams.tv.ui.screens.settings.streams

import com.seastreams.tv.domain.model.StreamDisplay
import com.seastreams.tv.domain.streams.AttributeFilter
import com.seastreams.tv.domain.streams.FilterAttribute
import com.seastreams.tv.domain.streams.FormatterId
import com.seastreams.tv.domain.streams.MovieSeriesRange
import com.seastreams.tv.domain.streams.RangeRule
import com.seastreams.tv.domain.streams.RegexRules
import com.seastreams.tv.domain.streams.ResultLimits
import com.seastreams.tv.domain.streams.SizeLimits
import com.seastreams.tv.domain.streams.SortScope
import com.seastreams.tv.domain.streams.StreamFilters
import com.seastreams.tv.domain.streams.StreamFormatterChoice
import com.seastreams.tv.domain.streams.StreamSort
import com.seastreams.tv.domain.streams.ValueRange
import com.seastreams.tv.domain.streams.format.FormatterPreview
import com.seastreams.tv.domain.streams.format.PredefinedFormatters

const val STREAM_FILTERS_ROW_KEY = "stream-filters"
const val STREAM_SORT_ROW_KEY = "stream-sort"
const val STREAM_FORMATTER_ROW_KEY = "stream-formatter"

/** `Off`, or how many filter groups are in use (`3 filters on`). */
internal fun filtersSummary(filters: StreamFilters): String {
    val count = activeFilterGroups(filters)
    return when (count) {
        0 -> "Off — every stream is shown"
        1 -> "1 filter on"
        else -> "$count filters on"
    }
}

internal fun activeFilterGroups(filters: StreamFilters): Int =
    FilterAttribute.entries.count { !filters.attribute(it).isEmpty } +
        listOf(
            filters.regex != RegexRules(),
            filters.seeders != RangeRule(),
            filters.ageHours != RangeRule(),
            filters.size != SizeLimits(),
            filters.bitrate != SizeLimits(),
            filters.limits != ResultLimits(),
            filters.dedup.enabled,
        ).count { it }

/** `Off`, or the Global list's first criteria (`Global: Resolution, Size +1`) and which type lists override it. */
internal fun sortSummary(sort: StreamSort): String {
    val global = sort.global
    val overrides = listOf(SortScope.MOVIES, SortScope.SERIES, SortScope.ANIME).filter { sort.list(it).isNotEmpty() }
    if (global.isEmpty() && overrides.isEmpty()) return "Off — addon order"
    val head = if (global.isEmpty()) "Global: none" else "Global: " + criteriaPreview(global.map { it.key.label })
    return if (overrides.isEmpty()) head else head + " · " + overrides.joinToString("/") { it.label } + " own"
}

private fun criteriaPreview(labels: List<String>): String =
    labels.take(2).joinToString(", ") + if (labels.size > 2) " +${labels.size - 2}" else ""

/** `None`, or the values in order (`2160p, 1080p +2`). */
internal fun listSummary(values: List<String>): String = if (values.isEmpty()) "None" else criteriaPreview(values)

/** `Required 1 · Excluded 3`, or `Not filtered`. */
internal fun attributeSummary(filter: AttributeFilter): String {
    val parts = listOfNotNull(
        filter.required.size.takeIf { it > 0 }?.let { "Required $it" },
        filter.excluded.size.takeIf { it > 0 }?.let { "Excluded $it" },
        filter.included.size.takeIf { it > 0 }?.let { "Included $it" },
        filter.preferred.size.takeIf { it > 0 }?.let { "Preferred $it" },
    )
    return if (parts.isEmpty()) "Not filtered" else parts.joinToString(" · ")
}

/** Seeder / age ranges, e.g. `Required 10+ · Excluded up to 2`. */
internal fun rangeSummary(rule: RangeRule, unit: String): String {
    fun range(r: ValueRange): String? = when {
        r.isOpen -> null
        r.max == null -> "${r.min}$unit+"
        r.min == null -> "up to ${r.max}$unit"
        else -> "${r.min}–${r.max}$unit"
    }
    val parts = listOfNotNull(
        range(rule.required)?.let { "Required $it" },
        range(rule.excluded)?.let { "Excluded $it" },
        range(rule.included)?.let { "Included $it" },
    )
    return if (parts.isEmpty()) "Not filtered — set in the Web Portal" else parts.joinToString(" · ")
}

/** `Global movies up to 30 GB · 2 resolutions`. */
internal fun limitsSummary(limits: SizeLimits): String {
    if (limits == SizeLimits()) return "Not filtered — set in the Web Portal"
    val own = limits.byResolution.count { (_, range) -> range != MovieSeriesRange() }
    val global = if (limits.global != MovieSeriesRange()) "Global set" else "No global limit"
    return if (own == 0) global else "$global · $own resolution${if (own == 1) "" else "s"}"
}

/** A result limit's label: `Off` or the cap. */
internal fun limitLabel(cap: Int?): String = cap?.toString() ?: "Off"

/** The next / previous step of a result limit: Off → 1, 2, 3, 5, 10, 20, 30, 40, 50 → Off. */
internal fun stepLimit(cap: Int?, delta: Int): Int? {
    val steps = listOf<Int?>(null) + LIMIT_STEPS
    val index = steps.indexOf(cap)
    if (index >= 0 || cap == null) return steps[(index.coerceAtLeast(0) + delta).mod(steps.size)]
    // A cap typed elsewhere (e.g. 7) steps to the nearest preset in that direction.
    return if (delta > 0) LIMIT_STEPS.firstOrNull { it > cap } else LIMIT_STEPS.lastOrNull { it < cap }
}

private val LIMIT_STEPS = listOf(1, 2, 3, 5, 10, 20, 30, 40, 50)

/** The sample release as [choice] shows it; `null` for the addon's own text or a template that fails. */
internal fun previewDisplay(choice: StreamFormatterChoice, nowMillis: Long): StreamDisplay? {
    val templates = PredefinedFormatters.templates(choice.id, choice.customName, choice.customDescription) ?: return null
    return try {
        FormatterPreview.render(templates, nowMillis)
    } catch (e: RuntimeException) {
        null
    }
}

/** `Custom (from the Web Portal)` when custom templates are stored. */
internal fun formatterLabel(id: FormatterId, choice: StreamFormatterChoice): String = when {
    id == FormatterId.CUSTOM && (choice.customName != null || choice.customDescription != null) -> "Custom (your templates)"
    id == FormatterId.CUSTOM -> "Custom (edit in the Web Portal)"
    else -> id.label
}
