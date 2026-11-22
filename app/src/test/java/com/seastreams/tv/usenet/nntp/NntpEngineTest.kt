package com.seastreams.tv.usenet.nntp

import com.seastreams.tv.testutil.FakeNntpServer
import com.seastreams.tv.testutil.YencTestEncoder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

class NntpEngineTest {
    private val primary = FakeNntpServer()
    private val second = FakeNntpServer()
    private val backup = FakeNntpServer()
    private val now = AtomicLong(1_000)
    private val engine = NntpEngine(DefaultSocketOpener, { now.get() }, null)
    private val settings = NntpEngineSettings(dialTimeoutMs = 2_000, stallTimeoutMs = 2_000, dialBackoffMs = 20)
    private val data = Random(42).nextBytes(1_000)
    private val body = YencTestEncoder.encode(data, part = 1, total = 1)

    @AfterEach
    fun tearDown() {
        engine.close()
        listOf(primary, second, backup).forEach { it.close() }
    }

    private fun fetch(id: String) = engine.fetch(id, 1, FetchPriority.HIGH).await(10_000)

    @Test
    fun `a 430 on the primary fails over to the backup, and the negative cache skips the primary for 60 s`() {
        backup.put("a@t", body)
        engine.configure(listOf(primary.endpoint("P", id = 1), backup.endpoint("B", isBackup = true, id = 2)), settings)
        assertArrayEquals(data, fetch("a@t").toByteArray())
        assertEquals(1, primary.bodyCount("a@t"))
        assertEquals(1, backup.bodyCount("a@t"))
        fetch("a@t")
        assertEquals(1, primary.bodyCount("a@t"), "remembered as missing on the primary")
        now.addAndGet(ProviderSelector.NEGATIVE_TTL_MS)
        fetch("a@t")
        assertEquals(2, primary.bodyCount("a@t"), "the negative entry expired")
    }

    @Test
    fun `missing everywhere is ArticleNotFound for all providers`() {
        engine.configure(listOf(primary.endpoint("P", id = 1), backup.endpoint("B", isBackup = true, id = 2)), settings)
        val error = assertThrows<ArticleNotFoundException> { fetch("gone@t") }
        assertTrue(error.allProviders)
        val again = assertThrows<ArticleNotFoundException> { fetch("gone@t") }
        assertTrue(again.allProviders, "answered from the negative cache")
        assertEquals(1, primary.bodyCount("gone@t"))
    }

    @Test
    fun `missing on one provider while the other fails is missing, but not for all providers`() {
        engine.configure(listOf(primary.endpoint("P", id = 1), backup.endpoint("B", isBackup = true, id = 2, pass = "bad")), settings)
        val error = assertThrows<ArticleNotFoundException> { fetch("gone@t") }
        assertFalse(error.allProviders)
    }

    @Test
    fun `no 430 anywhere reports the transport error instead of missing`() {
        primary.passAnswer = "481 Authentication rejected"
        engine.configure(listOf(primary.endpoint("P", id = 1)), settings)
        assertEquals(NntpErrorKind.AUTH_FAILED, assertThrows<NntpException> { fetch("a@t") }.kind)
    }

    @Test
    fun `backups are asked only after every primary priority group`() {
        backup.put("a@t", body)
        second.put("a@t", body)
        engine.configure(
            listOf(backup.endpoint("B", isBackup = true, priority = 0, id = 3), second.endpoint("S", priority = 5, id = 2), primary.endpoint("P", priority = 1, id = 1)),
            settings,
        )
        assertEquals(listOf("P", "S", "B"), engine.selector.order(engine.pools()).map { it.endpoint.name })
        fetch("a@t")
        assertEquals(1, primary.bodyCount("a@t"))
        assertEquals(1, second.bodyCount("a@t"))
        assertEquals(0, backup.bodyCount("a@t"))
    }

    @Test
    fun `a CRC-corrupt copy moves on to the next provider`() {
        primary.put("a@t", body)
        primary.corrupt += "a@t"
        backup.put("a@t", body)
        engine.configure(listOf(primary.endpoint("P", id = 1), backup.endpoint("B", isBackup = true, id = 2)), settings)
        assertArrayEquals(data, fetch("a@t").toByteArray())
        assertEquals(1, backup.bodyCount("a@t"))
    }

    @Test
    fun `a connection-limited primary keeps serving without being marked bad`() {
        primary.sessionLimit = 1
        primary.answerDelayMs = 3
        val file = primary.addFile(Random(1).nextBytes(40 * 500), partSize = 500)
        engine.configure(listOf(primary.endpoint("P", maxConnections = 5, pipelineDepth = 1, id = 1)), settings)
        val fetches = file.segments.map { engine.fetch(it.messageId, it.number, FetchPriority.HIGH) }
        fetches.forEach { it.await(15_000) }
        val pool = engine.pools().single()
        assertEquals(ProviderWorkerPool.State.ONLINE, pool.state)
        assertEquals(1, pool.throttle.allowed())
    }

    @Test
    fun `pools are kept by fingerprint and replaced when the login changes`() {
        engine.configure(listOf(primary.endpoint("P", id = 1)), settings)
        val first = engine.pools().single()
        engine.configure(listOf(primary.endpoint("Renamed", id = 1)), settings)
        assertSame(first, engine.pools().single(), "a rename keeps the pool")
        engine.configure(listOf(primary.endpoint("P", id = 1, pass = "other")), settings)
        assertNotSame(first, engine.pools().single())
        assertEquals(ProviderWorkerPool.State.CLOSED, first.state)
        assertEquals(4 * 2, engine.downloadPermits().capacity, "auto = connections × depth")
        engine.configure(listOf(primary.endpoint("P", id = 1, pass = "other")), settings.copy(maxConcurrentDownloads = 3))
        assertEquals(3, engine.downloadPermits().capacity)
    }

    @Test
    fun `warm connections are signed in ahead and reused by the pool`() {
        primary.put("a@t", body)
        engine.configure(listOf(primary.endpoint("P", id = 1)), settings.copy(warmConnections = 2))
        engine.warmUp()
        val endpoint = engine.pools().single().endpoint
        val deadline = System.currentTimeMillis() + 5_000
        while (engine.warm.heldCount(endpoint) < 2 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(2, engine.warm.heldCount(endpoint))
        assertEquals(2, primary.accepted.get())
        assertArrayEquals(data, fetch("a@t").toByteArray())
        assertEquals(2, primary.accepted.get(), "the fetch used a warm sign-in")
        assertEquals(1, engine.warm.heldCount(endpoint))
    }

    @Test
    fun `warm connections are pinged with DATE and closed after the idle TTL`() {
        val warm = WarmConnections(clock = { now.get() }, dialer = { it.run() })
        val local = settings.copy(keepAliveMs = 30_000, idleTtlMs = 60_000)
        val endpoint = primary.endpoint("P")
        warm.warm(endpoint, 1, local)
        assertEquals(1, warm.heldCount(endpoint))
        now.addAndGet(30_000)
        warm.sweep()
        assertEquals(1, primary.dateCommands.get())
        assertEquals(1, warm.heldCount(endpoint))
        now.addAndGet(30_000)
        warm.sweep()
        assertEquals(0, warm.heldCount(endpoint))
        warm.close()
    }

    @Test
    fun `cancelling a queued fetch never completes it`() {
        primary.stalled += "slow@t"
        primary.put("b@t", body)
        engine.configure(listOf(primary.endpoint("P", maxConnections = 1, pipelineDepth = 1, id = 1)), settings.copy(maxConcurrentDownloads = 1))
        val slow = engine.fetch("slow@t", 1, FetchPriority.HIGH)
        val queued = engine.fetch("b@t", 1, FetchPriority.HIGH)
        queued.cancel()
        assertTrue(queued.cancelled)
        slow.cancel()
        assertEquals(0, engine.downloadPermits().waiting)
    }
}
