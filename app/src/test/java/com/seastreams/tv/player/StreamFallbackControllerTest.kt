package com.seastreams.tv.player

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamFallbackControllerTest {

    private val advances = mutableListOf<Triple<Int, Int, FallbackReason>>()
    private val exhausted = mutableListOf<FallbackReason>()

    private fun TestScope.controller(timeoutSeconds: Int = 5) = StreamFallbackController(
        scope = backgroundScope,
        timeoutMs = { timeoutSeconds * 1_000L },
        onAdvance = { from, to, reason -> advances += Triple(from, to, reason) },
        onExhausted = { exhausted += it },
    )

    @Test
    fun `a stream that never reaches READY advances to the next one when the timeout passes`() = runTest {
        val fallback = controller(timeoutSeconds = 5)
        fallback.onStreamStarted(index = 0, count = 3)

        advanceTimeBy(4_999)
        runCurrent()
        assertTrue(advances.isEmpty(), "must not advance before the timeout")

        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf(Triple(0, 1, FallbackReason.TIMEOUT)), advances)
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 3, 10])
    fun `the configured 1s to 10s timeout is honoured`(seconds: Int) = runTest {
        val fallback = controller(timeoutSeconds = seconds)
        fallback.onStreamStarted(0, 2)

        advanceTimeBy(seconds * 1_000L - 1)
        runCurrent()
        assertTrue(advances.isEmpty())
        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, advances.size)
    }

    @Test
    fun `reaching READY in time cancels the timer`() = runTest {
        val fallback = controller()
        fallback.onStreamStarted(0, 3)
        advanceTimeBy(3_000)
        fallback.onReady()

        advanceTimeBy(60_000)
        runCurrent()
        assertTrue(advances.isEmpty())
        assertTrue(exhausted.isEmpty())
    }

    @Test
    fun `a playback or decoder error advances immediately`() = runTest {
        val fallback = controller()
        fallback.onStreamStarted(1, 4)
        advanceTimeBy(500)
        fallback.onError()

        assertEquals(listOf(Triple(1, 2, FallbackReason.ERROR)), advances)
        // The failed stream's timer is gone: no second advance from it.
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1, advances.size)
    }

    @Test
    fun `each new stream gets its own full timeout`() = runTest {
        val fallback = controller(timeoutSeconds = 2)
        fallback.onStreamStarted(0, 3)
        advanceTimeBy(1_500)
        fallback.onStreamStarted(1, 3) // e.g. the user hot-swapped streams

        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(advances.isEmpty(), "the first stream's timer must not fire for the second stream")
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(listOf(Triple(1, 2, FallbackReason.TIMEOUT)), advances)
    }

    @Test
    fun `running out of streams reports exhaustion instead of advancing`() = runTest {
        val fallback = controller()
        fallback.onStreamStarted(2, 3)
        fallback.onError()

        assertTrue(advances.isEmpty())
        assertEquals(listOf(FallbackReason.ERROR), exhausted)
    }

    @Test
    fun `timeouts also exhaust the last stream`() = runTest {
        val fallback = controller(timeoutSeconds = 1)
        fallback.onStreamStarted(0, 1)
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(listOf(FallbackReason.TIMEOUT), exhausted)
    }

    @Test
    fun `disabled fallback (trailers) never advances`() = runTest {
        val fallback = controller()
        fallback.enabled = false
        fallback.onStreamStarted(0, 3)
        advanceTimeBy(30_000)
        runCurrent()
        fallback.onError()

        assertTrue(advances.isEmpty())
        assertTrue(exhausted.isEmpty())
    }

    @Test
    fun `cancel stops a pending timeout`() = runTest {
        val fallback = controller()
        fallback.onStreamStarted(0, 3)
        fallback.cancel()
        advanceTimeBy(30_000)
        runCurrent()
        fallback.onError()

        assertTrue(advances.isEmpty())
    }
}
