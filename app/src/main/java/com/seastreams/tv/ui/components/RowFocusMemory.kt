package com.seastreams.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusDirection

/**
 * The last-focused card of every catalog row on a page, keyed by the row's stable id. It is both
 * where `Up`/`Down` re-entry lands and which card the row keeps drawn wide (16:9) while unfocused.
 *
 * Owned by the page (above its `LazyColumn`), so a row scrolled far enough off screen to be
 * disposed still knows which card to restore when `Up`/`Down` brings focus back into it. Rows
 * never visited start at card `0`, and a row whose cards change (data refresh) starts over at `0`.
 */
@Stable
class RowFocusMemory {
    private class Entry(val index: MutableIntState, var contentKeys: List<String>)

    private val entries = mutableMapOf<String, Entry>()

    /**
     * The live last-focused index of row [rowKey]; created at `0` on first use. [contentKeys] are the
     * row's item keys: when they differ from the previous call the row was refreshed, so the index
     * resets to `0`.
     */
    fun stateFor(rowKey: String, contentKeys: List<String> = emptyList()): MutableIntState {
        val entry = entries.getOrPut(rowKey) { Entry(mutableIntStateOf(0), contentKeys) }
        if (entry.contentKeys != contentKeys) {
            entry.contentKeys = contentKeys
            entry.index.intValue = 0
        }
        return entry.index
    }

    fun lastFocused(rowKey: String): Int = entries[rowKey]?.index?.intValue ?: 0
}

@Composable
fun rememberRowFocusMemory(): RowFocusMemory = remember { RowFocusMemory() }

/** How focus entering an [ExpandingCatalogRow] is handled. */
enum class RowEntry {
    /** Not a vertical move (e.g. a programmatic request for card 1): keep the requested target. */
    DEFAULT,

    /** Land on the row's last-focused card, which is on screen. */
    FOCUS_LAST,

    /** Scroll the row to its last-focused card first (it is off screen), then focus it. */
    SCROLL_THEN_FOCUS_LAST,
}

/**
 * `Up`/`Down` into a row always returns to its last-focused card, never to whichever card happens
 * to sit spatially above/below the previously focused one (the 16:9 expansion of the focused card
 * shifts every card's X position, so spatial search drifts sideways).
 */
fun rowEntry(direction: FocusDirection, targetVisible: Boolean): RowEntry = when {
    direction != FocusDirection.Up && direction != FocusDirection.Down -> RowEntry.DEFAULT
    targetVisible -> RowEntry.FOCUS_LAST
    else -> RowEntry.SCROLL_THEN_FOCUS_LAST
}

/** The remembered card, clamped to the row's current cards (the row may have shrunk since). */
fun restoreIndex(lastFocused: Int, lastIndex: Int): Int = lastFocused.coerceIn(0, lastIndex.coerceAtLeast(0))

/** [expandedCardIndex] when no media card is wide (empty row, or `View More` holds focus). */
const val NO_EXPANDED_CARD = -1

/**
 * The media card a row draws as its 16:9 wide card: the last-focused card ([lastFocused], `0` by
 * default), whether or not the row holds focus. Clamped to the media cards (`0 until itemCount`) so
 * a remembered `View More` position never leaves an empty wide card behind; while `View More`
 * itself holds focus it is the row's only wide card.
 */
fun expandedCardIndex(lastFocused: Int, itemCount: Int, viewMoreFocused: Boolean): Int = when {
    itemCount <= 0 || viewMoreFocused -> NO_EXPANDED_CARD
    else -> lastFocused.coerceIn(0, itemCount - 1)
}

/** How a catalog card is drawn: [wide] = 16:9 backdrop instead of a 2:3 poster; [focusRing] = active D-pad highlight. */
data class CardAppearance(val wide: Boolean, val focusRing: Boolean)

/** Width follows the row's expanded card; the focus ring follows only real D-pad focus. */
fun cardAppearance(wide: Boolean, focused: Boolean): CardAppearance = CardAppearance(wide = wide, focusRing = focused)

/**
 * The index of a row's last focusable card: `View More` (index [itemCount], i.e. position
 * `itemCount + 1`) when [hasMore], otherwise the last media card. `Right` past it wraps to card 0.
 */
fun rowLastIndex(itemCount: Int, hasMore: Boolean): Int = if (hasMore) itemCount else itemCount - 1
