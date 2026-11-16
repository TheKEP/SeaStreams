package com.seastreams.tv.ui.components

import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.focus.FocusRequester
import com.seastreams.tv.ui.theme.requestFocusAfterFrame

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
