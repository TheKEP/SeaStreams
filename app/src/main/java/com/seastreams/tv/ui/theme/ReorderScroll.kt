package com.seastreams.tv.ui.theme

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.focus.FocusRequester

/** One laid-out lazy item: its index, lazy key, top offset and height (from `LazyListLayoutInfo.visibleItemsInfo`). */
data class ItemSpan(val index: Int, val offset: Int, val size: Int, val key: Any? = null)

/**
 * A committed lazy-list layout. [contentStart] / [contentEnd] bound the area a row must sit inside to count as
 * visible: the viewport *minus* its content padding. The padding strips are drawn, but a row inside them sits at
 * (or under) the screen edge, so it counts as off-screen.
 */
data class ListLayout(val items: List<ItemSpan>, val contentStart: Int, val contentEnd: Int)

/** What [revealItem] needs from a lazy list: [LazyListState] in the app, a model list in tests. */
interface RevealList {
    /** The layout of the last measure pass (scrolls remeasure synchronously, so it is fresh after each call). */
    val layout: ListLayout

    suspend fun scrollBy(pixels: Float)

    suspend fun scrollToItem(index: Int)
}

/** How to bring a lazy item fully into view. */
sealed interface Reveal {
    data object None : Reveal

    /** Scroll by [pixels] (negative = towards the top). */
    data class By(val pixels: Float) : Reveal

    /** Not laid out: jump to it first, then line its bottom up with the content end when [alignEnd] (it lies below). */
    data class ToItem(val index: Int, val alignEnd: Boolean) : Reveal
}

/** The scroll that makes item [index] fully visible inside the content area of [layout]. */
fun reveal(index: Int, layout: ListLayout): Reveal {
    val item = layout.items.firstOrNull { it.index == index }
    if (item == null) {
        val lastLaidOut = layout.items.maxOfOrNull { it.index } ?: return Reveal.ToItem(index, alignEnd = false)
        return Reveal.ToItem(index, alignEnd = index > lastLaidOut)
    }
    val bottom = item.offset + item.size
    val tallerThanContent = item.size > layout.contentEnd - layout.contentStart
    return when {
        item.offset < layout.contentStart -> Reveal.By((item.offset - layout.contentStart).toFloat())
        bottom > layout.contentEnd && tallerThanContent -> Reveal.By((item.offset - layout.contentStart).toFloat())
        bottom > layout.contentEnd -> Reveal.By((bottom - layout.contentEnd).toFloat())
        else -> Reveal.None
    }
}

/** Whether item [index] lies fully inside the content area of [layout]. */
fun isFullyVisible(index: Int, layout: ListLayout): Boolean = reveal(index, layout) == Reveal.None

/**
 * Whether [layout] already shows the saved order: the moved row ([key]) is laid out at [index], or it isn't laid
 * out at all (pushed out of the viewport; [revealItem] brings it back). A row still laid out at its old index means
 * the reordered list hasn't been measured yet.
 */
fun orderCommitted(layout: ListLayout, index: Int, key: Any): Boolean =
    layout.items.firstOrNull { it.key == key }?.let { it.index == index } ?: true

/**
 * Scrolls item [index] fully into view with the smallest move: a row below the viewport ends flush with the content
 * end (so a row moved down sits on the bottom edge, never jumps to the top), a row above starts at the content start.
 * Returns whether it ended fully visible.
 */
suspend fun RevealList.revealItem(index: Int, awaitFrame: suspend () -> Unit = ::awaitNextFrame): Boolean {
    repeat(REVEAL_STEPS) {
        when (val step = reveal(index, layout)) {
            Reveal.None -> return true
            is Reveal.By -> scrollBy(step.pixels)
            is Reveal.ToItem -> {
                scrollToItem(step.index)
                val landed = layout.items.firstOrNull { it.index == step.index }
                if (landed == null) {
                    awaitFrame()
                } else if (step.alignEnd) {
                    // At the top now: scroll back so its bottom meets the content end (clamped at the list start).
                    val end = landed.offset + landed.size - layout.contentEnd
                    if (end < 0 && landed.size <= layout.contentEnd - layout.contentStart) scrollBy(end.toFloat())
                }
            }
        }
    }
    return isFullyVisible(index, layout)
}

/** [LazyListState] as a [RevealList]: the content area leaves out the list's content padding. */
fun LazyListState.asRevealList(): RevealList = LazyRevealList(this)

private class LazyRevealList(private val state: LazyListState) : RevealList {
    override val layout: ListLayout
        get() {
            val info = state.layoutInfo
            return ListLayout(
                items = info.visibleItemsInfo.map { ItemSpan(it.index, it.offset, it.size, it.key) },
                contentStart = info.viewportStartOffset + info.beforeContentPadding,
                contentEnd = info.viewportEndOffset - info.afterContentPadding,
            )
        }

    override suspend fun scrollBy(pixels: Float) {
        state.scrollBy(pixels)
    }

    override suspend fun scrollToItem(index: Int) {
        state.scrollToItem(index)
    }
}

/**
 * Opening a list on a row that may start off-screen (the chosen option of a long picker): scrolls it into view, then
 * focuses [requester] once its row is composed, retrying a few frames. Returns whether focus landed.
 */
suspend fun LazyListState.focusItem(index: Int, requester: FocusRequester, maxFrames: Int = FOCUS_FRAMES): Boolean {
    if (index >= 0) asRevealList().revealItem(index)
    return retryEachFrame(maxFrames) { requester.tryRequestFocus() }
}

private const val REVEAL_STEPS = 3
internal const val FOCUS_FRAMES = 10
