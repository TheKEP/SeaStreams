package com.seastreams.tv.usenet.nntp

import com.seastreams.tv.testutil.FakeNntpServer
import com.seastreams.tv.usenet.yenc.SegmentBufferPool
import com.seastreams.tv.usenet.yenc.YencDecoder
import com.seastreams.tv.usenet.yenc.YencException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

class ProviderWorkerPoolTest {
    private val server = FakeNntpServer()
    private val now = AtomicLong(1_000)
    private val clock = { now.get() }
    private val pools = ArrayList<ProviderWorkerPool>()
    private val settings = NntpEngineSettings(dialTimeoutMs = 2_000, stallTimeoutMs = 2_000, dialBackoffMs = 20)

    @AfterEach
    fun tearDown() {
        pools.forEach { it.close() }
        server.close()
    }

    private fun pool(endpoint: NntpEndpoint, settings: NntpEngineSettings = this.settings) =
        ProviderWorkerPool(endpoint, settings, YencDecoder(SegmentBufferPool()), clock = clock).also { pools += it }

    @ParameterizedTest
    @ValueSource(ints = [1, 2, 3, 4, 8, 12, 16])
    fun `pipelined answers resolve their own requests in FIFO order`(depth: Int) {
        val data = Random(depth).nextBytes(64 * 1_000)
        val file = server.addFile(data, partSize = 1_000, idPrefix = "d$depth")
        server.answerDelayMs = 1
        val pool = pool(server.endpoint(maxConnections = 1, pipelineDepth = depth))
        val fetches = file.segments.map { pool.body(it.messageId, it.number, FetchPriority.HIGH) }
        fetches.forEachIndexed { index, fetch ->
            val segment = fetch.await(10_000)
            assertEquals(index * 1_000L, segment.begin)
            assertArrayEquals(data.copyOfRange(index * 1_000, index * 1_000 + 1_000), segment.toByteArray())
        }
        assertEquals(1, server.accepted.get())
        assertTrue(server.peakUnanswered.get() <= depth, "never more than the depth in flight")
        if (depth > 1) assertTrue(server.peakUnanswered.get() > 1, "depth $depth actually pipelined")
    }

    @Test
    fun `connections never exceed the provider maximum`() {
        val data = Random(2).nextBytes(100 * 500)
        val file = server.addFile(data, partSize = 500)
        server.answerDelayMs = 2
        val pool = pool(server.endpoint(maxConnections = 3, pipelineDepth = 1))
        val fetches = file.segments.map { pool.body(it.messageId, it.number, FetchPriority.HIGH) }
        fetches.forEach { it.await(10_000) }
        assertTrue(server.peakActive.get() <= 3)
        assertTrue(pool.peakConnections in 2..3, "slots open lazily up to the limit")
    }

    @Test
    fun `a connection limit throttles to the open count instead of failing the account`() {
        val data = Random(3).nextBytes(60 * 500)
        val file = server.addFile(data, partSize = 500)
        server.sessionLimit = 2
        server.answerDelayMs = 5
        val pool = pool(server.endpoint(maxConnections = 6, pipelineDepth = 1))
        val fetches = file.segments.map { pool.body(it.messageId, it.number, FetchPriority.HIGH) }
        fetches.forEachIndexed { index, fetch -> assertEquals(index * 500L, fetch.await(15_000).begin) }
        assertEquals(ProviderWorkerPool.State.ONLINE, pool.state)
        assertTrue(pool.throttle.throttled)
        val allowed = pool.throttle.allowed()
        assertTrue(allowed in 1..2, "allowance dropped to the open count, was $allowed")
        now.addAndGet(ConnectionThrottle.STEP_MS)
        assertEquals(allowed + 1, pool.throttle.allowed(), "additive increase after 5 s")
        now.addAndGet(ConnectionThrottle.STEP_MS * 10)
        assertEquals(6, pool.throttle.allowed())
    }

    @Test
    fun `a bad login fails queued work and later submits at once`() {
        val pool = pool(server.endpoint(pass = "wrong"))
        val first = pool.body("a@test", 1, FetchPriority.HIGH)
        assertEquals(NntpErrorKind.AUTH_FAILED, assertThrows<NntpException> { first.await(5_000) }.kind)
        assertEquals(ProviderWorkerPool.State.AUTH_FAILED, pool.state)
        val accepted = server.accepted.get()
        val second = pool.body("b@test", 1, FetchPriority.HIGH)
        assertEquals(NntpErrorKind.AUTH_FAILED, assertThrows<NntpException> { second.await(1_000) }.kind)
        assertEquals(accepted, server.accepted.get(), "no new dial")
    }

    @Test
    fun `the circuit breaker rests an unreachable provider until its cooldown passes`() {
        val closedPort = ServerSocket(0).use { it.localPort }
        val endpoint = NntpEndpoint(9, "Down", "127.0.0.1", closedPort, false, "u", "p", 2, 1, 0, false)
        val pool = pool(endpoint, settings.copy(breakerThreshold = 3, breakerCooldownMs = 30_000))
        val failed = pool.body("x@test", 1, FetchPriority.HIGH)
        assertThrows<NntpException> { failed.await(10_000) }
        assertTrue(pool.breaker.isOpen())
        val fast = pool.body("y@test", 1, FetchPriority.HIGH)
        val error = assertThrows<NntpException> { fast.await(100) }
        assertTrue(error.message.orEmpty().contains("resting"))
        now.addAndGet(30_000)
        assertTrue(pool.healthy, "cooldown over: the provider is tried again")
    }

    @Test
    fun `430 fails only its request, and a CRC failure is reported without dropping the connection`() {
        val data = Random(4).nextBytes(3_000)
        val file = server.addFile(data, partSize = 1_000)
        server.corrupt += file.segments[1].messageId
        val pool = pool(server.endpoint(maxConnections = 1, pipelineDepth = 4))
        val missing = pool.body("gone@test", 1, FetchPriority.HIGH)
        val fetches = file.segments.map { pool.body(it.messageId, it.number, FetchPriority.HIGH) }
        assertTrue(assertThrows<ProviderMissingException> { missing.await(5_000) }.code == 430)
        assertEquals(0L, fetches[0].await(5_000).begin)
        assertEquals("crc_mismatch", assertThrows<YencException> { fetches[1].await(5_000) }.code)
        assertEquals(2_000L, fetches[2].await(5_000).begin)
        assertEquals(1, server.accepted.get())
    }

    @Test
    fun `a stalled connection is dropped and its requests re-queued on a new one`() {
        val data = Random(5).nextBytes(4_000)
        val file = server.addFile(data, partSize = 1_000)
        server.stallOnce += file.segments[1].messageId
        val pool = pool(server.endpoint(maxConnections = 1, pipelineDepth = 2), settings.copy(stallTimeoutMs = 300))
        val fetches = file.segments.map { pool.body(it.messageId, it.number, FetchPriority.HIGH) }
        fetches.forEachIndexed { index, fetch ->
            assertArrayEquals(data.copyOfRange(index * 1_000, index * 1_000 + 1_000), fetch.await(10_000).toByteArray())
        }
        assertTrue(server.accepted.get() >= 2)
        assertEquals(2, server.bodyCount(file.segments[1].messageId))
    }

    @Test
    fun `idle connections close after the idle TTL and STAT works`() {
        val data = Random(6).nextBytes(1_000)
        val file = server.addFile(data, partSize = 1_000)
        val pool = pool(server.endpoint(maxConnections = 2, pipelineDepth = 1), settings.copy(idleTtlMs = 50))
        assertTrue(pool.stat(file.segments[0].messageId, FetchPriority.LOW).await(5_000))
        assertEquals(false, pool.stat("nope@test", FetchPriority.LOW).await(5_000))
        now.addAndGet(100)
        val deadline = System.currentTimeMillis() + 5_000
        while (pool.openConnections > 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(0, pool.openConnections)
        assertTrue(pool.stat(file.segments[0].messageId, FetchPriority.LOW).await(5_000), "a new slot opens on demand")
    }
}
