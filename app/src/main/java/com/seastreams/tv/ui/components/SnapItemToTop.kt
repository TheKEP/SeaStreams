package com.seastreams.tv.ui.components

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.withFrameNanos
import kotlin.math.abs

/**
 * How long a focused page section takes to glide to the top of the viewport. Kept just under the
 * held-key vertical step interval (`VERTICAL_REPEAT_INTERVAL_MS`, 280ms), so each step settles the
 * newly focused row before the next one fires and focus never runs ahead of the scroll.
 */
internal const val SNAP_TO_TOP_MILLIS = 250

/** Furthest (in items) an off-screen target may be for the estimated glide; further jumps animate by index. */
private const val MAX_ESTIMATED_ITEMS = 2

/** Extra frames allowed after the glide's duration to absorb late layout changes before giving up. */
private const val SNAP_SETTLE_MILLIS = 400

/** Sub-pixel distances count as "at the top". */
private const val SNAP_EPSILON_PX = 0.5f

/**
 * Scrolls item [index] of a vertical page so its top sits at the start of the list's content area
 * (just below the top content padding, so never under the nav bar).
 *
 * `animateScrollToItem` measures the distance once and corrects afterwards. On `Down` the row above
 * collapses its info panel while that scroll is running, so the target moves up by the collapsed
 * height on its own. The scroll then overshoots, clipping the focused row's title, and snaps back.
 * This glide reads the target's offset from the latest layout on every frame and moves only a
 * fraction of what is left ([glideStepFraction]), so it follows the collapse instead of racing it.
 * Its final frames move the full remaining distance, so it lands exactly at the top.
 *
 * A neighbouring target that is not laid out yet (e.g. `Up` into the row just above the viewport)
 * glides towards an estimated offset until it scrolls into the layout, then switches to its real
 * offset, so it gets the same bounded [SNAP_TO_TOP_MILLIS] glide instead of an open-ended spring.
 * Far jumps fall back to `animateScrollToItem`.
 */
suspend fun LazyListState.snapItemToTop(index: Int) = glideItemToStart(index, SNAP_TO_TOP_MILLIS, FastOutSlowInEasing)

/**
 * The glide behind [snapItemToTop], for any lazy list orientation: moves item [index] to the start
 * of the content area over [durationMs], following [easing] while re-measuring the target each
 * frame (so it tracks items that resize mid-glide, e.g. a catalog card widening to 16:9). Running
 * inside [LazyListState.scroll], a new glide cancels one in flight and continues from where it was.
 * A target that cannot reach the start (the list is at its scroll limit) stops when time runs out.
 */
suspend fun LazyListState.glideItemToStart(index: Int, durationMs: Int, easing: Easing) {
    if (targetOffset(index) == null) {
        animateScrollToItem(index)
        return
    }
    var lost = false
    scroll {
        val start = withFrameNanos { it }
        var eased = 0f
        while (true) {
            val now = withFrameNanos { it }
            val remaining = targetOffset(index)
            if (remaining == null) {
                lost = true
                break
            }
            val elapsedMs = (now - start) / 1_000_000f
            val next = glideProgress(elapsedMs / durationMs, easing)
            val consumed = if (abs(remaining) < SNAP_EPSILON_PX) 0f else scrollBy(remaining * glideStepFraction(eased, next))
            eased = next
            val finished = next >= 1f && (abs(remaining) < SNAP_EPSILON_PX || abs(consumed) < SNAP_EPSILON_PX)
            if (finished || elapsedMs > durationMs + SNAP_SETTLE_MILLIS) break
        }
    }
    // Scrolled out of the laid-out window mid-glide (should not happen): land on it directly.
    if (lost) scrollToItem(index)
}

/**
 * Offset of item [index] from the start of the content area: exact when it is laid out, estimated
 * from the visible items' average size when it is within [MAX_ESTIMATED_ITEMS] of them, else `null`.
 */
internal fun LazyListState.targetOffset(index: Int): Float? {
    val info = layoutInfo
    val visible = info.visibleItemsInfo
    visible.firstOrNull { it.index == index }?.let { return it.offset.toFloat() }
    val first = visible.firstOrNull() ?: return null
    val last = visible.last()
    val spacing = info.mainAxisItemSpacing.toFloat()
    val pitch = visible.sumOf { it.size }.toFloat() / visible.size + spacing
    return estimatedOffset(index, first.index, first.offset, last.index, last.offset + last.size + spacing, pitch)
}

/**
 * Estimated offset of an item that is not laid out, [pitch] (average item size plus spacing) per
 * item before the first visible item ([firstIndex] at [firstOffset]) or after the last ([lastIndex],
 * whose successor would start at [afterLastOffset]). `null` when the target is more than
 * [MAX_ESTIMATED_ITEMS] away, or inside the visible range (it would have an exact offset).
 */
internal fun estimatedOffset(
    index: Int,
    firstIndex: Int,
    firstOffset: Int,
    lastIndex: Int,
    afterLastOffset: Float,
    pitch: Float,
): Float? = when {
    index < firstIndex && firstIndex - index <= MAX_ESTIMATED_ITEMS -> firstOffset - (firstIndex - index) * pitch
    index > lastIndex && index - lastIndex <= MAX_ESTIMATED_ITEMS -> afterLastOffset + (index - lastIndex - 1) * pitch
    else -> null
}

/**
 * The share of the *remaining* distance to move this frame, so that progress follows the eased
 * curve from [eased] to [next] (both in `0..1`) while the remaining distance is re-measured each
 * frame. Always in `0..1`, which means a step never passes the target. Once [next] reaches `1` it
 * returns `1`, so the remaining distance is covered completely.
 */
internal fun glideStepFraction(eased: Float, next: Float): Float {
    if (next >= 1f || eased >= 1f) return 1f
    return ((next - eased) / (1f - eased)).coerceIn(0f, 1f)
}

/** A glide's eased progress: [easing] on `t` (clamped to `0..1`). */
internal fun glideProgress(t: Float, easing: Easing = FastOutSlowInEasing): Float = easing.transform(t.coerceIn(0f, 1f))
