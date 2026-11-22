package com.seastreams.tv.ui.screens.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import com.seastreams.tv.ui.components.SeaIcons

/** The Settings Category Tab Bar's tabs, left to right (PRD §11, `settings-top-nav.jpg`). */
enum class SettingsCategory(val label: String, private val iconProvider: () -> ImageVector) {
    HOME("Home", { Icons.Filled.Home }),
    UI("UI", { SeaIcons.Palette }),
    PLAYBACK("Playback", { Icons.Filled.PlayArrow }),
    AUDIO("Audio", { SeaIcons.Volume }),
    SUBTITLES("Subtitles", { SeaIcons.Subtitles }),
    OTHER("Other", { Icons.Filled.Build }),
    ;

    /** The small icon in front of the uppercase category header. */
    val icon: ImageVector get() = iconProvider()
}

/**
 * The outward keys tab [index] of the Settings Category Tab Bar owns under the two-way §1 rule:
 * `Left` on `Home` (wraps to `Other`), `Right` on `Other` (wraps to `Home`), none on middle tabs.
 * Only a fresh press wraps; a held key stops on the edge tab ([com.seastreams.tv.ui.theme.onDpadEdgeWrap]).
 */
fun categoryEdgeKeys(index: Int, lastIndex: Int = SettingsCategory.entries.lastIndex): Set<Key> = buildSet {
    if (lastIndex < 0 || index !in 0..lastIndex) return@buildSet
    if (index == 0) add(Key.DirectionLeft)
    if (index == lastIndex) add(Key.DirectionRight)
}

/** Where a wrap from tab [index] lands: the last tab from the first, the first from the last. */
fun categoryWrapTarget(index: Int, key: Key, lastIndex: Int = SettingsCategory.entries.lastIndex): Int? = when {
    key !in categoryEdgeKeys(index, lastIndex) -> null
    key == Key.DirectionLeft -> lastIndex
    else -> 0
}

/** What an outward D-pad press on a Settings category row's edge does (PRD §11, one-way bottom-to-top). */
enum class SettingsRowEdge {
    /** Fresh `Up` on the first row: focus the active category pill (never wraps to the bottom). */
    TO_TAB_BAR,

    /** Fresh `Down` on the last row: scroll to the top and focus the first row. */
    WRAP_TO_FIRST,
}

/**
 * The edge keys row [ordinal] of a category list ending at [lastOrdinal] owns: `Up` → tab bar on the
 * first row, `Down` → wrap on the last, both on a single row (where the wrap keeps focus in place).
 * Held keys stop on the edge row ([com.seastreams.tv.ui.theme.onDpadEdgeWrap]); middle rows own none.
 */
fun settingsRowEdges(ordinal: Int, lastOrdinal: Int): Map<Key, SettingsRowEdge> = buildMap {
    if (lastOrdinal < 0 || ordinal !in 0..lastOrdinal) return@buildMap
    if (ordinal == 0) put(Key.DirectionUp, SettingsRowEdge.TO_TAB_BAR)
    if (ordinal == lastOrdinal) put(Key.DirectionDown, SettingsRowEdge.WRAP_TO_FIRST)
}

/** What Remote `Back` does on Settings, innermost first (PRD §11 *Back*). */
enum class SettingsBackAction {
    /** A sub-panel (Manage addons, Web portal, Subtitle styling, a choice list) closes onto its row. */
    CLOSE_PANEL,

    /** The open background swatch strip reverts its preview and refocuses its row. */
    REVERT_SWATCHES,

    /** A settings row hands focus back up to the active category tab. */
    FOCUS_ACTIVE_PILL,

    /** On the tab bar: Settings closes and the `Settings` gear regains focus (ProfileShell). */
    EXIT,
}

fun settingsBackAction(panelOpen: Boolean, swatchesOpen: Boolean, focusInList: Boolean): SettingsBackAction = when {
    panelOpen -> SettingsBackAction.CLOSE_PANEL
    swatchesOpen -> SettingsBackAction.REVERT_SWATCHES
    focusInList -> SettingsBackAction.FOCUS_ACTIVE_PILL
    else -> SettingsBackAction.EXIT
}
