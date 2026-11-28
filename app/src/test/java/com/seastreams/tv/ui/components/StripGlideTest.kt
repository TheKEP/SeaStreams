package com.seastreams.tv.ui.components

import com.seastreams.tv.ui.theme.HORIZONTAL_REPEAT_INTERVAL_MS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/** The catalog row strip's critically damped glide (PRD §4 Catalog Row Scroll Speed). */
class StripGlideTest {

    private val frameMs = 1000f / 60f
    private val pitch = 157f * 2f // px at 2x density

    /** One simulated frame: the strip's absolute [position] and its [velocity] (px/ms). */
    private data class Sample(val position: Float, val velocity: Float)

    /** Simulates [frames] of the strip glide from 0 towards the goal [goalAt] each frame. */
    private fun simulate(frames: Int, goalAt: (Int) -> Float): List<Sample> {
        var position = 0f
        var lead = 0f
        var velocity = 0f
        return (0 until frames).map { frame ->
            val step = glideFrame(goalAt(frame) - position, lead, velocity, pitch, frameMs)
            position += step.scrollDelta
            lead = step.virtualLead
            velocity = step.velocity
            Sample(position, velocity)
        }
    }

    private fun settledAfterMs(path: List<Sample>, goal: Float, from: Int = 0): Float {
        val frame = (from until path.size).first { glideSettled(goal - path[it].position, path[it].velocity) }
        return (frame - from + 1) * frameMs
    }

    @Test
    fun `a single step converges onto the target without overshoot`() {
        val path = simulate(60) { pitch }
        assertTrue(path.all { it.position <= pitch }, "never passes the target")
        assertTrue(path.zipWithNext().all { (a, b) -> b.position >= a.position }, "monotonic")
        assertTrue(abs(path.last().position - pitch) < SETTLE_DISTANCE_PX)
    }

    @Test
    fun `a tap glides about a quarter second, slower than the old 120ms glide`() {
        val path = simulate(60) { pitch }
        val settledMs = settledAfterMs(path, pitch)
        assertTrue(settledMs in 200f..400f, "settled after $settledMs ms")
        // It eases out: the second half of the glide is slower than its peak.
        assertTrue(path.last().velocity < path.maxOf { it.velocity })
    }

    @Test
    fun `a held key flows at a steady speed instead of stopping at every poster`() {
        val tickFrames = (HORIZONTAL_REPEAT_INTERVAL_MS / frameMs).toInt()
        val steps = 10
        val path = simulate(tickFrames * steps) { frame -> pitch * (frame / tickFrames + 1) }
        assertTrue(path.zipWithNext().all { (a, b) -> b.position >= a.position }, "never moves backwards")
        // After the first two steps the strip never comes to rest between held ticks.
        val cruising = path.drop(tickFrames * 2).dropLast(tickFrames)
        val minSpeed = cruising.minOf { it.velocity }
        val maxSpeed = cruising.maxOf { it.velocity }
        assertTrue(minSpeed > 0.5f, "keeps its momentum between ticks: $minSpeed px/ms")
        assertTrue(maxSpeed / minSpeed < 1.5f, "steady speed: $minSpeed..$maxSpeed px/ms")
    }

    @Test
    fun `releasing a held key decelerates onto the last focused poster`() {
        val tickFrames = (HORIZONTAL_REPEAT_INTERVAL_MS / frameMs).toInt()
        val finalTarget = pitch * 4
        val releaseFrame = tickFrames * 3
        val path = simulate(releaseFrame + 60) { frame -> pitch * minOf(frame / tickFrames + 1, 4) }
        assertTrue(path.all { it.position <= finalTarget }, "no overshoot")
        val settledMs = settledAfterMs(path, finalTarget, from = releaseFrame)
        assertTrue(settledMs < 600f, "settled $settledMs ms after the last step")
    }

    @Test
    fun `falling several posters behind catches up faster than one pitch per ramp`() {
        val path = simulate(40) { pitch * 4 }
        assertTrue(path[(CATALOG_ROW_RAMP_MS * 2 / frameMs).toInt()].position > pitch * 2.5f)
    }

    @Test
    fun `reversing direction stops on the new target without overshoot`() {
        var state = SmoothDamp(position = 100f, velocity = 2f)
        repeat(60) { state = smoothDampStep(state.position, 0f, state.velocity, CATALOG_ROW_SMOOTH_TIME_MS, frameMs) }
        assertTrue(state.position >= 0f)
        assertTrue(abs(state.position) < SETTLE_DISTANCE_PX)
    }

    @Test
    fun `settling needs both distance and velocity at rest`() {
        assertTrue(glideSettled(0.2f, 0.01f))
        assertFalse(glideSettled(0.2f, 1f))
        assertFalse(glideSettled(5f, 0f))
    }

    @Test
    fun `a zero-length frame changes nothing`() {
        assertEquals(SmoothDamp(3f, 1f), smoothDampStep(3f, 10f, 1f, CATALOG_ROW_SMOOTH_TIME_MS, 0f))
    }

    @Test
    fun `the slot crossfade is slower than before but still finishes within a tap's glide`() {
        assertTrue(SLOT_CROSSFADE_MS in 140..220)
    }
}
