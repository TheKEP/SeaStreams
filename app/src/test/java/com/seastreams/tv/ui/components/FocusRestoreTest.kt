package com.seastreams.tv.ui.components

import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * Focus restore when playback closes (PRD §9): the exact origin (`Play`, `Trailer`, `Shuffle`…) first,
 * then the Details primary `Play`/`Resume` fallback, never nothing.
 */
class FocusRestoreTest {

    /** A Details button: focusable once [attachedFromFrame] frames have passed (its layer was still covered). */
    private class FakeButton(val name: String, private val attachedFromFrame: Int = 1, private val detached: Boolean = false) {
        fun tryFocus(frame: Int): Boolean = !detached && frame >= attachedFromFrame
    }

    /** Mirrors `PlaybackHost`: origin (if any) then fallback (if any), retried each frame. */
    private suspend fun restore(origin: FakeButton?, fallback: FakeButton?, maxFrames: Int = 10): String? {
        var frame = 0
        val targets = listOfNotNull(origin, fallback)
        val index = restoreFocus(maxFrames, { frame++ }, targets.map { button -> { button.tryFocus(frame) } })
        return targets.getOrNull(index)?.name
    }

    private val play = FakeButton("Play")

    // ---- Tracker -----------------------------------------------------------------------------------

    @Test
    fun `the latest fallback wins and unregistering restores the previous one`() {
        val tracker = FocusOriginTracker()
        val outer = FocusRequester()
        val inner = FocusRequester()
        assertNull(tracker.fallback())
        val outerToken = tracker.registerFallback(outer)
        val innerToken = tracker.registerFallback(inner)
        assertSame(inner, tracker.fallback())
        tracker.unregisterFallback(innerToken)
        assertSame(outer, tracker.fallback())
        tracker.unregisterFallback(innerToken)
        assertSame(outer, tracker.fallback(), "unregistering twice is harmless")
        tracker.unregisterFallback(outerToken)
        assertNull(tracker.fallback())
    }

    @Test
    fun `an older Details leaving does not drop the newer fallback`() {
        val tracker = FocusOriginTracker()
        val first = tracker.registerFallback(FocusRequester())
        val newer = FocusRequester()
        tracker.registerFallback(newer)
        tracker.unregisterFallback(first)
        assertSame(newer, tracker.fallback())
    }

    @Test
    fun `the origin mark stays one-shot`() {
        val tracker = FocusOriginTracker()
        val origin = FocusRequester()
        tracker.mark(origin)
        assertSame(origin, tracker.take())
        assertNull(tracker.take())
    }

    // ---- Post-exit Details restore -------------------------------------------------------------------

    @Test
    fun `exiting playback returns focus to the button that started it`() = runTest {
        listOf("Play", "Resume", "Trailer", "Shuffle").forEach { name ->
            assertEquals(name, restore(origin = FakeButton(name), fallback = play))
        }
    }

    @Test
    fun `no origin mark (e g Continue Watching auto-play) lands on Play-Resume`() = runTest {
        assertEquals("Play", restore(origin = null, fallback = play))
    }

    @Test
    fun `a detached origin falls back to Play-Resume`() = runTest {
        assertEquals("Play", restore(origin = FakeButton("Trailer", detached = true), fallback = play))
    }

    @Test
    fun `while Details is still covered for a few frames the origin keeps priority`() = runTest {
        val trailer = FakeButton("Trailer", attachedFromFrame = 3)
        val coveredPlay = FakeButton("Play", attachedFromFrame = 3)
        assertEquals("Trailer", restore(origin = trailer, fallback = coveredPlay))
    }

    @Test
    fun `restore gives up after maxFrames when nothing can take focus`() = runTest {
        assertNull(restore(origin = FakeButton("Trailer", detached = true), fallback = null))
        assertNull(restore(origin = null, fallback = null))
        assertEquals(-1, restoreFocus(3, {}, listOf({ false })))
    }

    @Test
    fun `restore waits a frame before every attempt round`() = runTest {
        val log = mutableListOf<String>()
        var tries = 0
        restoreFocus(5, { log += "frame" }, listOf({ log += "origin"; false }, { log += "fallback"; ++tries == 2 }))
        assertEquals(listOf("frame", "origin", "fallback", "frame", "origin", "fallback"), log)
    }
}
