package com.seastreams.tv.ui.components

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HeldKeyTrailerGateTest {

    @Test
    fun `held Left stopping on item 0 re-arms the trailer on release`() {
        val gate = HeldKeyTrailerGate()
        // Fresh press moves focus 3 -> 2 (the card arms), repeats step 2 -> 1 -> 0 (each re-arms).
        assertFalse(gate.onKeyDown(repeatCount = 0))
        gate.onCardArmed()
        assertTrue(gate.onKeyDown(repeatCount = 1))
        gate.onCardArmed()
        assertTrue(gate.onKeyDown(repeatCount = 2))
        gate.onCardArmed()
        // Still held on item 0: repeats cancel the preview but no focus move re-arms it.
        assertTrue(gate.onKeyDown(repeatCount = 3))
        assertTrue(gate.onKeyDown(repeatCount = 4))

        assertTrue(gate.onKeyUp(), "release re-arms the hover delay on item 0")
        assertFalse(gate.onKeyUp(), "re-arms only once")
    }

    @Test
    fun `held Right stopping on the last card or View More re-arms on release`() {
        val gate = HeldKeyTrailerGate()
        assertTrue(gate.onKeyDown(repeatCount = 5))
        gate.onCardArmed() // the last card took focus
        assertTrue(gate.onKeyDown(repeatCount = 6)) // edge HOLD: consumed, focus stays

        assertTrue(gate.onKeyUp())
    }

    @Test
    fun `a held key whose last repeat moved focus does not restart the delay again on release`() {
        val gate = HeldKeyTrailerGate()
        assertTrue(gate.onKeyDown(repeatCount = 1))
        gate.onCardArmed()

        assertFalse(gate.onKeyUp())
    }

    @Test
    fun `a single tap never re-arms on release`() {
        val gate = HeldKeyTrailerGate()
        assertFalse(gate.onKeyDown(repeatCount = 0))
        gate.onCardArmed()

        assertFalse(gate.onKeyUp())
    }

    @Test
    fun `a fresh press forgets an earlier hold that ended elsewhere`() {
        val gate = HeldKeyTrailerGate()
        assertTrue(gate.onKeyDown(repeatCount = 3)) // a hold whose release went to another row
        assertFalse(gate.onKeyDown(repeatCount = 0)) // fresh tap at an edge: no focus move

        assertFalse(gate.onKeyUp())
    }
}
