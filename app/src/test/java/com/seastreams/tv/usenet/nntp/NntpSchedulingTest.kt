package com.seastreams.tv.usenet.nntp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Virtual-time tests of the breaker, the AIMD throttle, the priority semaphore and status classification. */
class NntpSchedulingTest {
    private var now = 1_000L
    private val clock = { now }

    @Test
    fun `circuit breaker opens after the threshold, rests for the cooldown, then re-opens on one failed probe`() {
        val breaker = CircuitBreaker(threshold = 3, cooldownMs = 30_000, clock = clock)
        assertFalse(breaker.recordFailure())
        assertFalse(breaker.recordFailure())
        assertTrue(breaker.recordFailure())
        assertTrue(breaker.isOpen())
        now += 29_999
        assertTrue(breaker.isOpen())
        now += 1
        assertFalse(breaker.isOpen())
        assertTrue(breaker.recordFailure(), "half-open: a single failure re-opens")
        now += 30_000
        breaker.recordSuccess()
        assertFalse(breaker.recordFailure())
        assertFalse(breaker.isOpen())
    }

    @Test
    fun `throttle drops to the open count and regrows by one every 5 s`() {
        val throttle = ConnectionThrottle(max = 10, clock = clock)
        assertEquals(10, throttle.allowed())
        throttle.onLimit(open = 4)
        assertEquals(4, throttle.allowed())
        assertTrue(throttle.throttled)
        now += 4_999
        assertEquals(4, throttle.allowed())
        now += 1
        assertEquals(5, throttle.allowed())
        now += 25_000
        assertEquals(10, throttle.allowed())
        assertFalse(throttle.throttled)
        throttle.onLimit(open = 0)
        assertEquals(1, throttle.allowed(), "never below one")
    }

    @Test
    fun `semaphore serves HIGH before LOW at the streaming share`() {
        val semaphore = PrioritySemaphore(capacity = 1, highShare = 0.8)
        val held = semaphore.acquireBlocking(FetchPriority.HIGH)
        val order = ArrayList<FetchPriority>()
        val permits = ArrayList<Permit>()
        repeat(10) { semaphore.acquire(FetchPriority.LOW) { p -> order += FetchPriority.LOW; permits += p } }
        repeat(10) { semaphore.acquire(FetchPriority.HIGH) { p -> order += FetchPriority.HIGH; permits += p } }
        held.release()
        while (permits.isNotEmpty()) permits.removeAt(0).release()
        assertEquals(20, order.size)
        // While both classes wait, 1 in 5 grants goes to LOW.
        assertEquals(listOf(FetchPriority.HIGH, FetchPriority.HIGH, FetchPriority.HIGH, FetchPriority.HIGH, FetchPriority.LOW), order.take(5))
        assertEquals(10, order.count { it == FetchPriority.HIGH })
    }

    @Test
    fun `strict priority with share 1, throttle and restore`() {
        val semaphore = PrioritySemaphore(capacity = 3, highShare = 1.0)
        val a = semaphore.acquireBlocking(FetchPriority.LOW)
        semaphore.throttleTo(1)
        val granted = ArrayList<String>()
        semaphore.acquire(FetchPriority.LOW) { granted += "low"; it.release() }
        semaphore.acquire(FetchPriority.HIGH) { granted += "high"; it.release() }
        assertEquals(2, semaphore.waiting)
        a.release()
        assertEquals(listOf("high", "low"), granted)
        semaphore.restore()
        assertEquals(3, semaphore.effectiveLimit)
        a.release()
        assertEquals(0, semaphore.inUse, "release is idempotent")
    }

    @Test
    fun `an interrupted acquire reports InterruptedIOException and leaks no permit`() {
        val semaphore = PrioritySemaphore(capacity = 1)
        val held = semaphore.acquireBlocking(FetchPriority.HIGH)
        val error = AtomicReference<Throwable>()
        val started = CountDownLatch(1)
        val thread = Thread {
            started.countDown()
            try {
                semaphore.acquireBlocking(FetchPriority.HIGH)
            } catch (e: Throwable) {
                error.set(e)
            }
        }
        thread.start()
        started.await()
        while (semaphore.waiting == 0) Thread.sleep(1)
        thread.interrupt()
        thread.join(2_000)
        assertTrue(error.get() is InterruptedIOException)
        held.release()
        assertEquals(0, semaphore.inUse)
    }

    @Test
    fun `a future awaited past its deadline times out, and an interrupt is InterruptedIOException`() {
        val future = NntpFuture<String>()
        assertEquals(NntpErrorKind.TIMEOUT, assertThrows<NntpException> { future.await(20) }.kind)
        val error = AtomicReference<Throwable>()
        val thread = Thread { runCatching { future.await() }.onFailure(error::set) }
        thread.start()
        Thread.sleep(50)
        thread.interrupt()
        thread.join(2_000)
        assertTrue(error.get() is InterruptedIOException)
        future.complete("ok")
        assertEquals("ok", future.await(10))
        val cancelled = NntpFuture<String>().also { it.cancel() }
        assertThrows<FetchCancelledException> { cancelled.await(10) }
        val done = CountDownLatch(1)
        cancelled.onDone { done.countDown() }
        assertTrue(done.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun `status classification separates bad logins from connection limits`() {
        assertEquals(NntpErrorKind.ARTICLE_NOT_FOUND, classifyStatus(430, "No such article"))
        assertEquals(NntpErrorKind.AUTH_FAILED, classifyStatus(481, "Authentication rejected"))
        assertEquals(NntpErrorKind.AUTH_FAILED, classifyStatus(480, "Authentication required"))
        assertEquals(NntpErrorKind.AUTH_FAILED, classifyStatus(482, "Authentication out of sequence"))
        assertEquals(NntpErrorKind.AUTH_FAILED, classifyStatus(482, "Authentication failed: invalid password"))
        assertEquals(NntpErrorKind.CONNECTION_LIMIT, classifyStatus(481, "Too many connections for your user"))
        assertEquals(NntpErrorKind.CONNECTION_LIMIT, classifyStatus(400, "Max connections reached"))
        assertEquals(NntpErrorKind.AUTH_FAILED, classifyStatus(452, "Denied", duringAuth = true))
        assertEquals(NntpErrorKind.PROTOCOL, classifyStatus(452, "Denied"))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "502 Authentication Failed",
            "502 Access denied",
            "502 Permission denied - check your username",
            "502 Login incorrect",
            "502 Your account has expired",
            "482 Authentication temporarily unavailable",
        ],
    )
    fun `a 482 or 502 about the login or account is a bad login, wherever it comes`(line: String) {
        val code = line.take(3).toInt()
        val text = line.drop(4)
        assertEquals(NntpErrorKind.AUTH_FAILED, classifyStatus(code, text))
        assertEquals(NntpErrorKind.AUTH_FAILED, classifyStatus(code, text, duringAuth = true))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "502 Too many connections",
            "502 Too many connections for user abc",
            "482 Too many simultaneous sessions",
            "502 Maximum number of connections reached for your account",
            "502 Connection limit exceeded, access denied",
            "482 You have exceeded your connection allowance",
        ],
    )
    fun `a 482 or 502 is the connection limit only when it says so`(line: String) {
        val code = line.take(3).toInt()
        val text = line.drop(4)
        assertEquals(NntpErrorKind.CONNECTION_LIMIT, classifyStatus(code, text))
        assertEquals(NntpErrorKind.CONNECTION_LIMIT, classifyStatus(code, text, duringAuth = true))
    }

    @Test
    fun `an unexplained 502 is a refused connection, or a refused sign-in during AUTHINFO`() {
        assertEquals(NntpErrorKind.CONNECTION, classifyStatus(502, "Service unavailable"))
        assertEquals(NntpErrorKind.CONNECTION, classifyStatus(502, ""))
        assertEquals(NntpErrorKind.AUTH_FAILED, classifyStatus(502, "Service unavailable", duringAuth = true))
        assertEquals(NntpErrorKind.AUTH_FAILED, classifyStatus(482, "", duringAuth = true))
    }

    @Test
    fun `the server reply is shown cleaned up and capped`() {
        assertEquals("502 Access denied", NntpStatus(502, "Access denied").describe())
        assertEquals("502", NntpStatus(502, "\u0007 \t").describe())
        val long = NntpStatus(502, "x".repeat(400)).describe()
        assertTrue(long.length < 170 && long.endsWith("…"))
    }
}
