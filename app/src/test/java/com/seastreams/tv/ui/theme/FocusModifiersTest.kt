package com.seastreams.tv.ui.theme

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FocusModifiersTest {

    @Test
    fun `fresh presses are never throttled`() {
        val throttle = DpadRepeatThrottle(minIntervalMs = 280L)
        listOf(0L, 1L, 2L, 50L, 51L).forEach { time ->
            assertTrue(throttle.accept(repeatCount = 0, eventTimeMs = time), "t=$time")
        }
    }

    @Test
    fun `rapid repeats within the interval are consumed`() {
        val throttle = DpadRepeatThrottle(minIntervalMs = 280L)
        assertTrue(throttle.accept(repeatCount = 0, eventTimeMs = 1_000L))
        // Typical TV auto-repeat: ~50ms apart after the initial delay.
        listOf(1_050L, 1_100L, 1_150L, 1_200L, 1_250L, 1_279L).forEachIndexed { i, time ->
            assertFalse(throttle.accept(repeatCount = i + 1, eventTimeMs = time), "t=$time")
        }
    }

    @Test
    fun `repeats at or after the interval pass through and restart it`() {
        val throttle = DpadRepeatThrottle(minIntervalMs = 280L)
        assertTrue(throttle.accept(repeatCount = 0, eventTimeMs = 1_000L))
        assertTrue(throttle.accept(repeatCount = 1, eventTimeMs = 1_280L))
        assertFalse(throttle.accept(repeatCount = 2, eventTimeMs = 1_330L))
        assertTrue(throttle.accept(repeatCount = 3, eventTimeMs = 1_600L))
    }

    @Test
    fun `holding a key for a second steps about three and a half times per second`() {
        val throttle = DpadRepeatThrottle()
        val accepted = (0..20).count { i -> throttle.accept(repeatCount = i, eventTimeMs = i * 50L) }
        // Accepted at 0, 300, 600, 900ms (repeats arrive on a 50ms grid).
        assertEquals(4, accepted)
    }

    @Test
    fun `first repeat seen by a fresh throttle passes`() {
        assertTrue(DpadRepeatThrottle().accept(repeatCount = 7, eventTimeMs = 5_000L))
    }

    @Test
    fun `fresh press right after a held step is still instant`() {
        val throttle = DpadRepeatThrottle()
        assertTrue(throttle.accept(repeatCount = 4, eventTimeMs = 2_000L))
        assertTrue(throttle.accept(repeatCount = 0, eventTimeMs = 2_010L))
        assertFalse(throttle.accept(repeatCount = 1, eventTimeMs = 2_100L))
    }

    @Test
    fun `grid cadence lets held repeats through every 250ms while fresh presses stay instant`() {
        val throttle = DpadRepeatThrottle(minIntervalMs = 250L)
        assertTrue(throttle.accept(repeatCount = 0, eventTimeMs = 0L))
        // Repeats on a 50ms grid for one second: accepted at 250, 500, 750, 1000ms only.
        val accepted = (1..20).filter { i -> throttle.accept(repeatCount = i, eventTimeMs = i * 50L) }
        assertEquals(listOf(5, 10, 15, 20), accepted)
        assertTrue(throttle.accept(repeatCount = 0, eventTimeMs = 1_010L))
    }

    @Test
    fun `rapid back-to-back Left and Right taps are never throttled`() {
        val throttle = DpadRepeatThrottle(minIntervalMs = HORIZONTAL_REPEAT_INTERVAL_MS)
        // Taps 5–20ms apart, alternating and repeating directions.
        listOf(RIGHT to 0L, RIGHT to 5L, RIGHT to 10L, LEFT to 15L, RIGHT to 20L, LEFT to 40L, LEFT to 45L)
            .forEach { (key, time) ->
                assertTrue(throttle.accept(repeatCount = 0, eventTimeMs = time, direction = key), "key=$key t=$time")
            }
    }

    @Test
    fun `held Left or Right steps every 150ms, faster than the 280ms vertical pace`() {
        assertTrue(HORIZONTAL_REPEAT_INTERVAL_MS < VERTICAL_REPEAT_INTERVAL_MS)
        val horizontal = DpadRepeatThrottle(minIntervalMs = HORIZONTAL_REPEAT_INTERVAL_MS)
        val vertical = DpadRepeatThrottle(minIntervalMs = VERTICAL_REPEAT_INTERVAL_MS)
        // One second of a held key: fresh press at 0ms, then repeats on a 50ms grid.
        val hSteps = (0..20).filter { i -> horizontal.accept(repeatCount = i, eventTimeMs = i * 50L, direction = RIGHT) }
        val vSteps = (0..20).count { i -> vertical.accept(repeatCount = i, eventTimeMs = i * 50L, direction = DOWN) }
        assertEquals(listOf(0, 3, 6, 9, 12, 15, 18), hSteps) // 0, 150, 300 … 900ms
        assertEquals(4, vSteps)
    }

    @Test
    fun `held horizontal repeats inside 150ms are consumed`() {
        val throttle = DpadRepeatThrottle(minIntervalMs = HORIZONTAL_REPEAT_INTERVAL_MS)
        assertTrue(throttle.accept(repeatCount = 0, eventTimeMs = 1_000L, direction = LEFT))
        listOf(1_050L, 1_100L, 1_149L).forEachIndexed { i, time ->
            assertFalse(throttle.accept(repeatCount = i + 1, eventTimeMs = time, direction = LEFT), "t=$time")
        }
        assertTrue(throttle.accept(repeatCount = 4, eventTimeMs = 1_150L, direction = LEFT))
    }

    @Test
    fun `direction reversal restarts the repeat interval`() {
        val throttle = DpadRepeatThrottle(minIntervalMs = HORIZONTAL_REPEAT_INTERVAL_MS)
        assertTrue(throttle.accept(repeatCount = 0, eventTimeMs = 0L, direction = RIGHT))
        assertTrue(throttle.accept(repeatCount = 3, eventTimeMs = 150L, direction = RIGHT))
        // A Left repeat right after a Right step is a reversal: accepted, and it starts a new interval.
        assertTrue(throttle.accept(repeatCount = 1, eventTimeMs = 160L, direction = LEFT))
        assertFalse(throttle.accept(repeatCount = 2, eventTimeMs = 200L, direction = LEFT))
        assertTrue(throttle.accept(repeatCount = 3, eventTimeMs = 310L, direction = LEFT))
    }

    @Test
    fun `held Right throttling still hands the edge a hold, and a fresh press there wraps`() {
        val throttle = DpadRepeatThrottle(minIntervalMs = HORIZONTAL_REPEAT_INTERVAL_MS)
        // A repeat the throttle lets through reaches the last card, which must hold, not wrap.
        assertTrue(throttle.accept(repeatCount = 6, eventTimeMs = 900L, direction = RIGHT))
        assertEquals(EdgeKeyAction.HOLD, edgeKeyAction(repeatCount = 6))
        // A fresh Right press is never throttled and is the only thing that wraps.
        assertTrue(throttle.accept(repeatCount = 0, eventTimeMs = 905L, direction = RIGHT))
        assertEquals(EdgeKeyAction.WRAP, edgeKeyAction(repeatCount = 0))
    }

    private companion object {
        // android.view.KeyEvent key codes, as the throttle node passes them.
        const val LEFT = 21
        const val RIGHT = 22
        const val DOWN = 20
    }
}
