package com.seastreams.tv.ui.components

import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import com.seastreams.tv.domain.model.MediaItem
import com.seastreams.tv.ui.theme.requestFocusAfterFrame

/**
 * How focus entering a [PosterGrid] is handled. The grid doesn't use `focusRestorer`: its `onEnter`
 * also fires for a programmatic `requestFocus()` on a poster (direction `Enter`) and, ignoring the
 * direction, redirects it to the previously focused poster (e.g. to wherever a reordered title moved).
 */
enum class PosterGridEntry {
    /** A programmatic request: the requested poster keeps focus. */
    REQUESTED,

    /** A D-pad entry: land on the last-focused poster, which is composed. */
    RESTORE,

    /** A D-pad entry whose last-focused poster is scrolled away: scroll to it first, then focus it. */
    SCROLL_THEN_RESTORE,
}

/** Programmatic (`Enter`) requests keep their target; D-pad entries restore the last-focused poster. */
fun posterGridEntry(direction: FocusDirection, targetComposed: Boolean): PosterGridEntry = when {
    direction == FocusDirection.Enter -> PosterGridEntry.REQUESTED
    targetComposed -> PosterGridEntry.RESTORE
    else -> PosterGridEntry.SCROLL_THEN_RESTORE
}

/** Where the last-focused title sits now (it follows a reorder), else the first poster. */
fun posterGridRestoreIndex(lastFocusedKey: String?, items: List<MediaItem>): Int =
    lastFocusedKey?.let { key -> items.indexOfFirst { it.key == key } }?.takeIf { it >= 0 } ?: 0

/** The last poster that held focus in a grid, by key. Plain field: focus moves never recompose. */
class LastFocusedPoster {
    var key: String? = null
}

/**
 * Minimum time between accepted `Up`/`Down` steps while the key is held inside a poster grid
 * (~4 rows per second). Fresh presses are never delayed.
 */
const val POSTER_GRID_REPEAT_INTERVAL_MS = 250L

/**
 * The poster `Down` moves to from [index] in a [columns]-wide grid of [count] items: the same column
 * one row lower, or the last poster when the bottom row is too short to have that column. `null` on
 * the bottom row, where PRD §1 edge-stop-then-wrap takes over.
 */
fun gridDownTarget(index: Int, count: Int, columns: Int): Int? =
    if (index < 0 || index >= lastRowStart(count, columns)) null else minOf(index + columns, count - 1)

/** The poster `Up` moves to from [index]: the same column one row higher, `null` on the top row. */
fun gridUpTarget(index: Int, columns: Int): Int? = if (index < columns) null else index - columns

/**
 * Vertical grid scrolling that keeps a [peekPx] strip of the neighbouring rows on screen around the
 * focused poster. The row a `Up`/`Down` step lands on is therefore already composed, so focus moves
 * straight to it instead of falling back to whichever poster spatial search happens to find.
 */
class PeekBringIntoViewSpec(private val peekPx: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val leading = offset - peekPx
        val trailing = offset + size - (containerSize - peekPx)
        return when {
            size + 2 * peekPx > containerSize -> leading
            leading < 0f -> leading
            trailing > 0f -> trailing
            else -> 0f
        }
    }
}

/**
 * Moves focus to poster [target] (reached from [from]). A composed target is focused at once and the
 * grid's [PeekBringIntoViewSpec] glides it into view; a target that is not composed yet (focus
 * outran the scroll) is first scrolled to the matching viewport edge, then focused.
 */
suspend fun LazyGridState.focusGridItem(from: Int, target: Int, columns: Int, requester: FocusRequester) {
    val info = layoutInfo
    if (info.visibleItemsInfo.none { it.index == target }) {
        val current = info.visibleItemsInfo.firstOrNull { it.index == from }
        if (current == null) {
            scrollToItem(target)
        } else {
            val stride = current.size.height + info.mainAxisItemSpacing
            val rows = target / columns - from / columns
            val targetTop = current.offset.y + rows * stride
            val distance = if (rows > 0) {
                targetTop + current.size.height - (info.viewportEndOffset - info.afterContentPadding)
            } else {
                targetTop - (info.viewportStartOffset + info.beforeContentPadding)
            }
            scrollBy(distance.toFloat())
        }
    }
    requester.requestFocusAfterFrame()
}
