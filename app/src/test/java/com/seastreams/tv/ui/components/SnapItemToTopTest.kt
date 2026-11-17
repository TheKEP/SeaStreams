package com.seastreams.tv.ui.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SnapItemToTopTest {

    @Test
    fun `easing is clamped and ends at one`() {
        assertEquals(0f, glideProgress(-1f))
        assertEquals(0f, glideProgress(0f))
        assertEquals(1f, glideProgress(1f))
        assertEquals(1f, glideProgress(2f))
    }

    @Test
    fun `glide settles inside the held-key step interval`() {
        assertTrue(SNAP_TO_TOP_MILLIS < com.seastreams.tv.ui.theme.VERTICAL_REPEAT_INTERVAL_MS)
    }

    @Test
    fun `easing is monotonic`() {
        var previous = 0f
        for (frame in 1..100) {
            val value = glideProgress(frame / 100f)
            assertTrue(value >= previous, "frame $frame: $value < $previous")
            previous = value
        }
    }

    @Test
    fun `row just above the viewport is estimated one pitch above the first visible row`() {
        assertEquals(-400f, estimatedOffset(4, firstIndex = 5, firstOffset = 0, lastIndex = 6, afterLastOffset = 900f, pitch = 400f))
    }

    @Test
    fun `row just below the viewport starts where the last visible row ends`() {
        assertEquals(900f, estimatedOffset(7, firstIndex = 5, firstOffset = 0, lastIndex = 6, afterLastOffset = 900f, pitch = 400f))
        assertEquals(1300f, estimatedOffset(8, firstIndex = 5, firstOffset = 0, lastIndex = 6, afterLastOffset = 900f, pitch = 400f))
    }

    @Test
    fun `far or visible targets have no estimate`() {
        assertEquals(null, estimatedOffset(1, firstIndex = 5, firstOffset = 0, lastIndex = 6, afterLastOffset = 900f, pitch = 400f))
        assertEquals(null, estimatedOffset(20, firstIndex = 5, firstOffset = 0, lastIndex = 6, afterLastOffset = 900f, pitch = 400f))
        assertEquals(null, estimatedOffset(5, firstIndex = 5, firstOffset = 0, lastIndex = 6, afterLastOffset = 900f, pitch = 400f))
    }

    @Test
    fun `a step never passes the target`() {
        var eased = 0f
        for (frame in 1..20) {
            val next = glideProgress(frame / 20f)
            val fraction = glideStepFraction(eased, next)
            assertTrue(fraction in 0f..1f, "frame $frame fraction $fraction")
            eased = next
        }
    }

    @Test
    fun `final frame covers the whole remaining distance`() {
        assertEquals(1f, glideStepFraction(eased = 0.97f, next = 1f))
        assertEquals(1f, glideStepFraction(eased = 1f, next = 1f))
    }

    @Test
    fun `row above collapsing mid-glide lands exactly on the target without overshoot`() {
        // Down from row N: row N+1 starts below the top while row N's 124px info panel collapses over
        // the first 8 of 15 frames (130ms of a 250ms glide at 60fps), i.e. the target moves up on its
        // own while the glide runs.
        val collapsePerFrame = 124f / 8
        listOf(600f, 400f, 300f).forEach { start ->
            var offset = start
            var eased = 0f
            for (frame in 1..15) {
                val next = glideProgress(frame / 15f)
                // The glide reads the previous frame's layout; this frame's scroll and collapse land together.
                val step = offset * glideStepFraction(eased, next)
                eased = next
                offset -= step + if (frame <= 8) collapsePerFrame else 0f
                assertTrue(offset >= -0.01f, "start $start overshot at frame $frame: $offset")
            }
            assertEquals(0f, offset, 0.01f)
        }
    }
}
