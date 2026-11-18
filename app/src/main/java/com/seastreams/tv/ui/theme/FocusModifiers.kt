package com.seastreams.tv.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.KeyInputModifierNode
import androidx.compose.ui.input.key.type
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.unit.dp

/** Stroke width of the focus ring on focused cards (catalog cards and the Home hero). */
val FocusBorderWidth = 3.dp

/**
 * The card focus ring ([FocusBorderWidth] of [FocusPill]) for containers that are not themselves a
 * TV `Surface`, e.g. a card whose focus lives on a child button. Draws nothing unless [visible].
 */
fun Modifier.focusRing(visible: Boolean, shape: Shape): Modifier =
    if (visible) border(BorderStroke(FocusBorderWidth, FocusPill), shape) else this

/** Disables automatic focus scrolling for a container that scrolls itself (page snapping, row glides). */
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

/** What [onDpadEdgeWrap] does with one key event. */
enum class EdgeKeyResult {
    /** Not this edge's key, or not a key-down: let it through untouched. */
    PASS,

    /** Held key at the edge: consume it so focus stays here. */
    HOLD,

    /** Fresh press at the edge: consume it and wrap. */
    WRAP,
}

/** Pure decision behind [onDpadEdgeWrap], for an event that is ([matchesKey]) or isn't the edge key. */
fun edgeKeyResult(matchesKey: Boolean, isKeyDown: Boolean, repeatCount: Int): EdgeKeyResult = when {
    !matchesKey || !isKeyDown -> EdgeKeyResult.PASS
    edgeKeyAction(repeatCount) == EdgeKeyAction.WRAP -> EdgeKeyResult.WRAP
    else -> EdgeKeyResult.HOLD
}

/**
 * Bidirectional edge-stop-then-wrap (PRD §1). Put it on an edge element with that edge's outward
 * [key]: the last element of a row (`Right`, wrap to the first) or of a page (`Down`, wrap to the
 * top), and the first element (`Left`, wrap to the last; `Up`, wrap to the bottom-most element).
 * Holding [key] stops focus here; a fresh press of [key] while already here runs [wrap]. Both cases
 * consume the press, so focus never escapes past the edge. A single-element row takes both its
 * `Left` and `Right` edges, so either press is consumed and focus stays put.
 */
fun Modifier.onDpadEdgeWrap(key: Key, wrap: () -> Unit): Modifier = onPreviewKeyEvent { event ->
    val result = edgeKeyResult(
        matchesKey = event.key == key,
        isKeyDown = event.type == KeyEventType.KeyDown,
        repeatCount = event.nativeKeyEvent.repeatCount,
    )
    if (result == EdgeKeyResult.WRAP) wrap()
    result != EdgeKeyResult.PASS
}

/** Minimum time between accepted steps while `Up`/`Down` is held (~3.5 rows per second). */
const val VERTICAL_REPEAT_INTERVAL_MS = 280L

/** Minimum time between accepted steps while `Left`/`Right` is held (~6.5 cards per second). */
const val HORIZONTAL_REPEAT_INTERVAL_MS = 150L

/**
 * Paces a held D-pad key. A fresh press (`repeatCount == 0`) is always accepted at once; an
 * auto-repeat is accepted only once [minIntervalMs] has passed since the last accepted step, unless
 * it is for a different [direction] than that step (a reversal), which is accepted and restarts the
 * interval.
 */
class DpadRepeatThrottle(private val minIntervalMs: Long = VERTICAL_REPEAT_INTERVAL_MS) {
    private var lastAcceptedAtMs: Long? = null
    private var lastDirection: Int? = null

    /**
     * Whether the key-down at [eventTimeMs] may move focus; accepting it starts a new interval.
     * [direction] identifies the key (e.g. its key code); `0` when only one direction matters.
     */
    fun accept(repeatCount: Int, eventTimeMs: Long, direction: Int = 0): Boolean {
        val last = lastAcceptedAtMs
        val accepted = repeatCount == 0 || last == null || direction != lastDirection ||
            eventTimeMs - last >= minIntervalMs
        if (accepted) {
            lastAcceptedAtMs = eventTimeMs
            lastDirection = direction
        }
        return accepted
    }
}

/** The D-pad keys one throttle paces; keys on the other axis always pass through untouched. */
private enum class DpadAxis(val keys: Set<Key>) {
    VERTICAL(setOf(Key.DirectionUp, Key.DirectionDown)),
    HORIZONTAL(setOf(Key.DirectionLeft, Key.DirectionRight)),
}

/**
 * Paces held `Up`/`Down` inside a vertical page so focus never outruns the page's snap-to-top glide.
 * Fresh presses pass straight through; auto-repeats closer than [minIntervalMs] to the last accepted
 * step are consumed before focus search runs. `Left`/`Right` are never touched. Apply it to the page
 * container: it sees key events before its children, so edge-stop-then-wrap ([onDpadEdgeWrap]) on
 * the bottom-most element still receives every repeat it lets through and every fresh press.
 */
fun Modifier.throttleVerticalDpadRepeat(minIntervalMs: Long = VERTICAL_REPEAT_INTERVAL_MS): Modifier =
    this then DpadRepeatThrottleElement(DpadAxis.VERTICAL, minIntervalMs)

/**
 * Paces held `Left`/`Right` inside a horizontal row (faster than [throttleVerticalDpadRepeat]).
 * Fresh taps (`repeatCount == 0`) always pass through instantly, however fast they come; auto-repeats
 * closer than [minIntervalMs] to the last accepted step are consumed, and reversing direction restarts
 * the interval. `Up`/`Down` are never touched. Apply it to the row container so the last card's
 * [onDpadEdgeWrap] still sees every fresh press (wrap) and every repeat let through (hold).
 */
fun Modifier.throttleHorizontalDpadRepeat(minIntervalMs: Long = HORIZONTAL_REPEAT_INTERVAL_MS): Modifier =
    this then DpadRepeatThrottleElement(DpadAxis.HORIZONTAL, minIntervalMs)

private data class DpadRepeatThrottleElement(val axis: DpadAxis, val minIntervalMs: Long) :
    ModifierNodeElement<DpadRepeatThrottleNode>() {
    override fun create() = DpadRepeatThrottleNode(axis, DpadRepeatThrottle(minIntervalMs))

    override fun update(node: DpadRepeatThrottleNode) {
        node.axis = axis
        node.throttle = DpadRepeatThrottle(minIntervalMs)
    }
}

/** Holds the throttle in the node, so its timing survives recomposition of the page or row. */
private class DpadRepeatThrottleNode(var axis: DpadAxis, var throttle: DpadRepeatThrottle) :
    Modifier.Node(), KeyInputModifierNode {
    override fun onPreKeyEvent(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown || event.key !in axis.keys) return false
        val native = event.nativeKeyEvent
        return !throttle.accept(native.repeatCount, native.eventTime, native.keyCode)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean = false
}

/** The D-pad / keyboard keys that click a focused control. */
val SelectKeys: Set<Key> = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)

/**
 * Key-up phantom-click guard (PRD §1). A control only lets a select key's repeats and release
 * through once it has seen that press start (`KeyDown`, `repeatCount == 0`) while focused. A press
 * that started on another control (the long-press that opened a modal, the `Enter` that opened the
 * Player HUD) is swallowed until released. Any focus change forgets the press.
 */
class InitialKeyUpGuard {
    private var pressSeen = false

    fun onFocusChanged() {
        pressSeen = false
    }

    /** Whether to consume this event so the control's own click handling never sees it. */
    fun shouldConsume(isSelectKey: Boolean, isKeyDown: Boolean, repeatCount: Int): Boolean {
        if (!isSelectKey) return false
        if (isKeyDown && repeatCount == 0) {
            pressSeen = true
            return false
        }
        if (!pressSeen) return true
        if (!isKeyDown) pressSeen = false
        return false
    }
}

/**
 * Applies [InitialKeyUpGuard] to the focusable it wraps (e.g. a TV `Surface`). The guard runs in the
 * preview pass, so the wrapped control never sees a release (or a held repeat) whose `KeyDown`
 * landed on a different control.
 */
fun Modifier.ignoreInitialKeyUp(): Modifier = this then IgnoreInitialKeyUpElement

private data object IgnoreInitialKeyUpElement : ModifierNodeElement<IgnoreInitialKeyUpNode>() {
    override fun create() = IgnoreInitialKeyUpNode()

    override fun update(node: IgnoreInitialKeyUpNode) = Unit
}

private class IgnoreInitialKeyUpNode : Modifier.Node(), KeyInputModifierNode, FocusEventModifierNode {
    private val guard = InitialKeyUpGuard()
    private var focused = false

    override fun onFocusEvent(focusState: FocusState) {
        if (focusState.isFocused != focused) {
            focused = focusState.isFocused
            guard.onFocusChanged()
        }
    }

    override fun onPreKeyEvent(event: KeyEvent): Boolean = guard.shouldConsume(
        isSelectKey = event.key in SelectKeys,
        isKeyDown = event.type == KeyEventType.KeyDown,
        repeatCount = event.nativeKeyEvent.repeatCount,
    )

    override fun onKeyEvent(event: KeyEvent): Boolean = false
}

/**
 * Requests focus once the next frame has been laid out, for targets that only become attached after
 * a scroll (lazy list items). Returns whether focus moved; an unattached requester is not an error.
 */
suspend fun FocusRequester.requestFocusAfterFrame(): Boolean {
    withFrameNanos { }
    return runCatching { requestFocus() }.getOrDefault(false)
}

/** Suspends until the next frame has run (a function, not a lambda default: see [retryEachFrame]). */
suspend fun awaitNextFrame() {
    withFrameNanos { }
}

/** Requests focus without throwing on an unattached requester; returns whether focus moved. */
fun FocusRequester.tryRequestFocus(): Boolean = runCatching { requestFocus() }.getOrDefault(false)

/**
 * Runs [attempt] once per frame, each time after [awaitFrame] lets the frame settle, until it
 * succeeds or [maxFrames] frames have passed. For focus targets that attach only after a lazy list
 * scroll and may need more than one frame on a slow device. Returns whether an attempt succeeded.
 */
suspend fun retryEachFrame(
    maxFrames: Int,
    awaitFrame: suspend () -> Unit = ::awaitNextFrame,
    attempt: () -> Boolean,
): Boolean {
    repeat(maxFrames) {
        awaitFrame()
        if (attempt()) return true
    }
    return false
}

/**
 * How far (px) a lazy-row child's trailing edge sits past the visible area's trailing edge, where
 * the visible area ends [afterContentPaddingPx] before [viewportEndOffset] so the end gutter stays
 * clear. `0` when the child already fits. Offsets are in `LazyListLayoutInfo` coordinates.
 */
fun trailingOverflowPx(itemOffset: Int, itemSize: Int, viewportEndOffset: Int, afterContentPaddingPx: Int): Int =
    (itemOffset + itemSize - (viewportEndOffset - afterContentPaddingPx)).coerceAtLeast(0)
