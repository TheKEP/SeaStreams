package com.seastreams.tv.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/** One [smoothDampStep] result: the new [position] and [velocity] (units per millisecond). */
data class SmoothDamp(val position: Float, val velocity: Float)

/**
 * One frame of a critically damped follow from [current] towards [target] ([smoothTimeMs] ≈ the time
 * to cover most of the distance; ~3.3× that to settle within 1%). [velocity] carries over between
 * frames, so a target that keeps moving (a held D-pad key) is followed at a steady speed instead of
 * easing in and out at every step. The result never passes [target] (zero overshoot).
 */
fun smoothDampStep(current: Float, target: Float, velocity: Float, smoothTimeMs: Float, dtMs: Float): SmoothDamp {
    if (dtMs <= 0f) return SmoothDamp(current, velocity)
    val omega = 2f / smoothTimeMs.coerceAtLeast(MIN_SMOOTH_TIME_MS)
    val x = omega * dtMs
    val decay = 1f / (1f + x + 0.48f * x * x + 0.235f * x * x * x)
    val change = current - target
    val temp = (velocity + omega * change) * dtMs
    var nextVelocity = (velocity - omega * temp) * decay
    var next = target + (change + temp) * decay
    // Passed the target: stop on it.
    if ((target - current > 0f) == (next > target)) {
        next = target
        nextVelocity = 0f
    }
    return SmoothDamp(next, nextVelocity)
}

/** Whether a follow [remaining] px from its target at [velocity] px/ms has come to rest. */
fun glideSettled(remaining: Float, velocity: Float): Boolean =
    abs(remaining) < SETTLE_DISTANCE_PX && abs(velocity) < SETTLE_VELOCITY_PX_PER_MS

/** One [glideFrame]: scroll by [scrollDelta]; carry [virtualLead] and [velocity] into the next frame. */
data class GlideFrame(val scrollDelta: Float, val virtualLead: Float, val velocity: Float)

/**
 * One frame of the strip glide, in px relative to the current scroll position ([remaining] = distance
 * to the goal item). A virtual target ([virtualLead]) moves towards the goal at one [pitchPx] per
 * [rampMs] (faster only when it has fallen more than a pitch behind), and the strip follows it with
 * [smoothDampStep]. With [rampMs] matched to the held-key step interval, a held key's goal moves
 * exactly as fast as the virtual target, so the strip cruises at a constant speed; a tap ramps one
 * pitch and eases out (~280ms), and releasing a held key decelerates onto the last focused poster.
 */
fun glideFrame(
    remaining: Float,
    virtualLead: Float,
    velocity: Float,
    pitchPx: Float,
    dtMs: Float,
    rampMs: Float = CATALOG_ROW_RAMP_MS,
    smoothTimeMs: Float = CATALOG_ROW_SMOOTH_TIME_MS,
): GlideFrame {
    val gap = remaining - virtualLead
    val maxMove = maxOf(pitchPx, abs(gap)) / rampMs.coerceAtLeast(MIN_SMOOTH_TIME_MS) * dtMs
    val lead = virtualLead + gap.coerceIn(-maxMove, maxMove)
    val step = smoothDampStep(0f, lead, velocity, smoothTimeMs, dtMs)
    return GlideFrame(step.position, lead - step.position, step.velocity)
}

/**
 * A catalog row strip's continuous glide (PRD §4 Catalog Row Scroll Speed). [retarget] sets the item
 * to bring to the content start (underneath the showcase slot); a single follow loop per row then
 * glides there frame by frame ([glideFrame], [pitchPx] = one poster plus its gap), re-measuring the
 * target every frame. Retargeting while it runs
 * only moves the goal, so the strip keeps its velocity: a held key reads as one fluid motion that
 * decelerates onto the focused poster on release. Any other scroll on [listState] (`scrollToItem` for
 * a wrap, restore or refresh) cancels the loop; the next [retarget] starts a new one.
 */
class StripGlider(
    private val listState: LazyListState,
    private val scope: CoroutineScope,
    private val pitchPx: Float,
) {
    private var target = 0
    private var job: Job? = null

    fun retarget(index: Int) {
        target = index
        if (job?.isActive == true) return
        job = scope.launch { follow() }
    }

    private suspend fun follow() {
        if (listState.targetOffset(target) == null) {
            listState.scrollToItem(target)
            return
        }
        var lostIndex: Int? = null
        listState.scroll {
            var velocity = 0f
            var lead = 0f
            var last = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                val dtMs = ((now - last) / NANOS_PER_MILLI).coerceIn(0f, MAX_FRAME_MS)
                last = now
                val goal = target
                val remaining = listState.targetOffset(goal)
                if (remaining == null) {
                    lostIndex = goal
                    break
                }
                if (glideSettled(remaining, velocity)) {
                    if (abs(remaining) > 0f) scrollBy(remaining)
                    break
                }
                val frame = glideFrame(remaining, lead, velocity, pitchPx, dtMs)
                val consumed = scrollBy(frame.scrollDelta)
                velocity = frame.velocity
                lead = frame.virtualLead + frame.scrollDelta - consumed
                // At the scroll limit (the goal cannot reach the start): stop rather than push forever.
                if (abs(frame.scrollDelta) > SETTLE_DISTANCE_PX && abs(consumed) < SETTLE_DISTANCE_PX / 2) break
            }
        }
        // Scrolled out of the laid-out window mid-glide (should not happen): land on it directly.
        lostIndex?.let { listState.scrollToItem(it) }
    }
}

private const val MIN_SMOOTH_TIME_MS = 1f
private const val NANOS_PER_MILLI = 1_000_000f

/** Longest frame the follow integrates at once, so a stalled frame never jumps the strip. */
private const val MAX_FRAME_MS = 50f

internal const val SETTLE_DISTANCE_PX = 0.5f
internal const val SETTLE_VELOCITY_PX_PER_MS = 0.02f
