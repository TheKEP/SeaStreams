package com.seastreams.tv.ui.screens.playback

import androidx.compose.ui.input.key.Key
import com.seastreams.tv.domain.model.StreamAddon
import com.seastreams.tv.domain.model.StreamSource

/** One addon card: the addon and the global indices (into the flat stream list) of its streams. */
internal data class AddonGroup(val addon: StreamAddon, val indices: List<Int>)

/**
 * One group per addon in [addons] order, each with its streams' global indices in list order; an
 * addon that returned nothing keeps an empty group. A stream whose addon isn't in [addons] (e.g. no
 * addon list was passed) gets a group of its own, in order of appearance, so no stream is ever hidden.
 */
internal fun addonGroups(addons: List<StreamAddon>, streams: List<StreamSource>): List<AddonGroup> {
    val byUrl = streams.indices.groupBy { streams[it].addonUrl }
    val known = addons.distinctBy { it.url }
    val knownUrls = known.mapTo(HashSet()) { it.url }
    val orphans = byUrl.filterKeys { it !in knownUrls }.map { (url, indices) -> StreamAddon(url, streams[indices.first()].addonName) }
    return (known + orphans).map { AddonGroup(it, byUrl[it.url].orEmpty()) }
}

/** Where the panel opens: the selected addon card and the focused stream in it (`null` = focus that card). */
internal data class InitialFocus(val addonIndex: Int, val streamPosition: Int?)

/**
 * The addon holding the `LAST USED` stream, focused on it; otherwise the 1st addon, focused on its
 * 1st stream, or on its card when it returned nothing. `null` when there are no addon cards.
 */
internal fun initialFocus(groups: List<AddonGroup>, lastUsedIndex: Int?): InitialFocus? {
    if (groups.isEmpty()) return null
    if (lastUsedIndex != null) {
        groups.forEachIndexed { addon, group ->
            val position = group.indices.indexOf(lastUsedIndex)
            if (position >= 0) return InitialFocus(addon, position)
        }
    }
    return InitialFocus(0, if (groups[0].indices.isEmpty()) null else 0)
}

/** The two columns of the stream panel. */
internal enum class PanelColumn { ADDONS, STREAMS }

/** What a `Left`/`Right` press does in the stream panel. */
internal enum class ColumnMove {
    /** Focus the selected addon's card in the `Addons` column. */
    TO_ACTIVE_ADDON,

    /** Focus the selected addon's first stream in the `Choose a stream` column. */
    TO_FIRST_STREAM,

    /** Consume the press; focus stays put. */
    HOLD,

    /** Not a column switch: ordinary handling (`Up`/`Down` wrap within the column) runs. */
    PASS,
}

/**
 * `Left`/`Right` in the two-column stream panel: `Right` on an addon card goes to its first stream
 * (held in place when [addonHasStreams] is false), `Left` on a stream card goes back to the selected
 * addon card, and the outer `Left` (addons) / `Right` (streams) are consumed so focus never leaves.
 */
internal fun columnSwitch(column: PanelColumn, key: Key, addonHasStreams: Boolean): ColumnMove = when (column) {
    PanelColumn.ADDONS -> when (key) {
        Key.DirectionRight -> if (addonHasStreams) ColumnMove.TO_FIRST_STREAM else ColumnMove.HOLD
        Key.DirectionLeft -> ColumnMove.HOLD
        else -> ColumnMove.PASS
    }
    PanelColumn.STREAMS -> when (key) {
        Key.DirectionLeft -> ColumnMove.TO_ACTIVE_ADDON
        Key.DirectionRight -> ColumnMove.HOLD
        else -> ColumnMove.PASS
    }
}
