package com.seastreams.tv.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp

/** Stroke width of the focus ring on focused cards (catalog cards and the Home hero). */
val FocusBorderWidth = 3.dp

/**
 * The card focus ring ([FocusBorderWidth] of [FocusPill]) for containers that are not themselves a
 * TV `Surface`, e.g. a card whose focus lives on a child button. Draws nothing unless [visible].
 */
fun Modifier.focusRing(visible: Boolean, shape: Shape): Modifier =
    if (visible) border(BorderStroke(FocusBorderWidth, FocusPill), shape) else this

/**
 * TV row scrolling: keeps the focused child's leading edge [pivotPx] from the viewport's leading
 * edge, so focus stays in a fixed spot while the content slides underneath it.
 */
class PivotBringIntoViewSpec(private val pivotPx: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = offset - pivotPx
}

/** Disables automatic focus scrolling for a container that scrolls itself (e.g. per-row snapping). */
object ManualBringIntoViewSpec : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

/**
 * Intercepts a D-pad key press before focus search runs. [action] returns `true` to consume it,
 * which suppresses the default focus move. Key-up events always pass through.
 */
fun Modifier.onDpadKeyDown(key: Key, action: () -> Boolean): Modifier = onPreviewKeyEvent { event ->
    event.type == KeyEventType.KeyDown && event.key == key && action()
}

/** What an edge element does with a D-pad press in its outward direction (PRD §1 edge-stop-then-wrap). */
enum class EdgeKeyAction {
    /** Key held down (auto-repeat): consume the event so focus stops and stays on the edge element. */
    HOLD,

    /** Fresh press while already on the edge: wrap to the first item / top of the page. */
    WRAP,
}

/** Auto-repeat events (`repeatCount > 0`) never wrap; only a fresh press (`repeatCount == 0`) does. */
fun edgeKeyAction(repeatCount: Int): EdgeKeyAction = if (repeatCount > 0) EdgeKeyAction.HOLD else EdgeKeyAction.WRAP

/**
 * Edge-stop-then-wrap for the last element of a row (`Right`) or the bottom-most row of a page
 * (`Down`). Holding [key] stops focus here; a fresh press of [key] while already here runs [wrap].
 * Both cases consume the press, so focus never escapes past the edge.
 */
fun Modifier.onDpadEdgeWrap(key: Key, wrap: () -> Unit): Modifier = onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown || event.key != key) return@onPreviewKeyEvent false
    if (edgeKeyAction(event.nativeKeyEvent.repeatCount) == EdgeKeyAction.WRAP) wrap()
    true
}

/**
 * Requests focus once the next frame has been laid out, for targets that only become attached after
 * a scroll (lazy list items). Returns whether focus moved; an unattached requester is not an error.
 */
suspend fun FocusRequester.requestFocusAfterFrame(): Boolean {
    withFrameNanos { }
    return runCatching { requestFocus() }.getOrDefault(false)
}

/**
 * How far (px) a lazy-row child's trailing edge sits past the visible area's trailing edge, where
 * the visible area ends [afterContentPaddingPx] before [viewportEndOffset] so the end gutter stays
 * clear. `0` when the child already fits. Offsets are in `LazyListLayoutInfo` coordinates.
 */
fun trailingOverflowPx(itemOffset: Int, itemSize: Int, viewportEndOffset: Int, afterContentPaddingPx: Int): Int =
    (itemOffset + itemSize - (viewportEndOffset - afterContentPaddingPx)).coerceAtLeast(0)
