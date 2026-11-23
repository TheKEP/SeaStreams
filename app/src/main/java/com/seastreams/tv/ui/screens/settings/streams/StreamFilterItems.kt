package com.seastreams.tv.ui.screens.settings.streams

import com.seastreams.tv.domain.streams.AttributeFilter
import com.seastreams.tv.domain.streams.DedupKey
import com.seastreams.tv.domain.streams.DedupMode
import com.seastreams.tv.domain.streams.FilterAttribute
import com.seastreams.tv.domain.streams.FilterList
import com.seastreams.tv.domain.streams.ResultLimits
import com.seastreams.tv.domain.streams.StreamFilters
import com.seastreams.tv.ui.screens.settings.PanelItem
import com.seastreams.tv.ui.screens.settings.RowTrailing
import com.seastreams.tv.ui.screens.settings.SettingsPanel

/** Edits one profile's filters; the store saves the result. */
typealias FiltersUpdate = ((StreamFilters) -> StreamFilters) -> Unit

/**
 * `Stream filters` (`aio-filters.png`): the attribute groups (each opens its four lists), the patterns and ranges typed
 * in the Web Portal (shown with their current values), result limits (`‹ value ›` steppers) and the deduplicator.
 */
internal fun streamFilterItems(filters: StreamFilters, open: (SettingsPanel) -> Unit, openPortal: () -> Unit, update: FiltersUpdate): List<PanelItem> =
    buildList {
        add(PanelItem.Section("sec-attributes", "Attributes"))
        FilterAttribute.entries.filter { it.options != null }.forEach { attribute ->
            add(row(attributeKey(attribute), attribute.label, attributeSummary(filters.attribute(attribute)), RowTrailing.Chevron) {
                open(SettingsPanel.Stream.FilterGroup(attribute))
            })
        }
        add(PanelItem.Section("sec-portal", "Typed in the Web Portal"))
        add(row("release-group", FilterAttribute.RELEASE_GROUP.label, portalSummary(filters.releaseGroups), RowTrailing.Chevron, openPortal))
        add(row("keyword", FilterAttribute.KEYWORD.label, portalSummary(filters.keywords), RowTrailing.Chevron, openPortal))
        add(row("regex", "Regex", regexSummary(filters), RowTrailing.Chevron, openPortal))
        add(row("seeders", "Seeders", rangeSummary(filters.seeders, ""), RowTrailing.Chevron, openPortal))
        add(row("age", "Age", rangeSummary(filters.ageHours, "h"), RowTrailing.Chevron, openPortal))
        add(row("size", "Size", limitsSummary(filters.size), RowTrailing.Chevron, openPortal))
        add(row("bitrate", "Bitrate (estimate)", limitsSummary(filters.bitrate), RowTrailing.Chevron, openPortal))
        add(PanelItem.Section("sec-limits", "Result limits"))
        LIMIT_ROWS.forEach { (key, title, get, set) ->
            val step: (Int) -> Unit = { delta -> update { f -> f.copy(limits = set(f.limits, stepLimit(get(f.limits), delta))) } }
            add(row(key, title, limitLabel(get(filters.limits)), RowTrailing.Stepper({ step(-1) }, { step(1) })) { step(1) })
        }
        add(PanelItem.Section("sec-dedup", "Deduplicator"))
        val dedup = filters.dedup
        add(row("dedup", "Remove duplicates", if (dedup.enabled) "On" else "Off", RowTrailing.Toggle(dedup.enabled)) {
            update { f -> f.copy(dedup = f.dedup.copy(enabled = !f.dedup.enabled)) }
        })
        if (dedup.enabled) {
            val cycleMode: () -> Unit = {
                update { f -> f.copy(dedup = f.dedup.copy(mode = DedupMode.entries[(f.dedup.mode.ordinal + 1) % DedupMode.entries.size])) }
            }
            add(row("dedup-mode", "Compare", dedup.mode.label, RowTrailing.Stepper(cycleMode, cycleMode), cycleMode))
            DedupKey.entries.forEach { key ->
                val on = key in dedup.keys
                add(row("dedup-${key.name}", "Match by ${key.label.lowercase()}", if (on) "On" else "Off", RowTrailing.Toggle(on)) {
                    update { f -> f.copy(dedup = f.dedup.copy(keys = if (key in f.dedup.keys) f.dedup.keys - key else f.dedup.keys + key)) }
                })
            }
        }
    }

/** One attribute's Required / Excluded / Included / Preferred rows, each opening its value list. */
internal fun filterGroupItems(attribute: FilterAttribute, filter: AttributeFilter, open: (SettingsPanel) -> Unit): List<PanelItem> =
    FilterList.entries.map { list ->
        row(listKey(list), "${list.label} ${plural(attribute)}", listSummary(filter.list(list)) + " · " + listHint(list), RowTrailing.Chevron) {
            open(SettingsPanel.Stream.FilterValues(attribute, list))
        }
    }

/** The `aio-filters.png` explanation of each list. */
internal fun listHint(list: FilterList): String = when (list) {
    FilterList.REQUIRED -> "anything else is removed"
    FilterList.EXCLUDED -> "these are removed"
    FilterList.INCLUDED -> "kept regardless of every other filter"
    FilterList.PREFERRED -> "ranked in this order by Stream sorting"
}

/** `qualities`, `visual tags`, `languages`. */
internal fun plural(attribute: FilterAttribute): String =
    if (attribute == FilterAttribute.QUALITY) "qualities" else attribute.label.lowercase() + "s"

internal fun attributeKey(attribute: FilterAttribute) = "attr-${attribute.name}"

internal fun listKey(list: FilterList) = "list-${list.name}"

private fun portalSummary(filter: AttributeFilter): String =
    if (filter.isEmpty) "Not filtered — type values in the Web Portal" else attributeSummary(filter)

private fun regexSummary(filters: StreamFilters): String {
    val r = filters.regex
    val parts = listOfNotNull(
        r.required.size.takeIf { it > 0 }?.let { "Required $it" },
        r.excluded.size.takeIf { it > 0 }?.let { "Excluded $it" },
        r.included.size.takeIf { it > 0 }?.let { "Included $it" },
        r.preferred.size.takeIf { it > 0 }?.let { "Preferred $it" },
    )
    return if (parts.isEmpty()) "Not filtered — type patterns in the Web Portal" else parts.joinToString(" · ")
}

private data class LimitRow(val key: String, val title: String, val get: (ResultLimits) -> Int?, val set: (ResultLimits, Int?) -> ResultLimits)

private val LIMIT_ROWS: List<LimitRow> = listOf(
    LimitRow("limit-global", "Total results", { it.global }, { l, v -> l.copy(global = v) }),
    LimitRow("limit-resolution", "Per resolution", { it.perResolution }, { l, v -> l.copy(perResolution = v) }),
    LimitRow("limit-quality", "Per quality", { it.perQuality }, { l, v -> l.copy(perQuality = v) }),
    LimitRow("limit-addon", "Per addon", { it.perAddon }, { l, v -> l.copy(perAddon = v) }),
)

private fun row(key: String, title: String, value: String, trailing: RowTrailing, onClick: () -> Unit) =
    PanelItem.Row(key, title, value, trailing, onClick)
