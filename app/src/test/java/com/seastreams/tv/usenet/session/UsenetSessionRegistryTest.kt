package com.seastreams.tv.usenet.session

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import com.seastreams.tv.player.usenet.NntpDataSource
import com.seastreams.tv.player.usenet.UsenetLoadErrorPolicy
import com.seastreams.tv.usenet.archive.ArchiveRefusedException
import com.seastreams.tv.usenet.archive.ByteVolume
import com.seastreams.tv.usenet.archive.UsenetUnplayableException
import com.seastreams.tv.usenet.nntp.ArticleNotFoundException
import com.seastreams.tv.usenet.nntp.NntpErrorKind
import com.seastreams.tv.usenet.nntp.NntpException
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

class UsenetSessionRegistryTest {
    private val payload = Random(1).nextBytes(100_000)
    private var now = 0L
    private val opened = AtomicInteger()

    private fun token(n: Int) = "%032x".format(n)

    private val trimmed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun registry(idleMs: Long = 60_000, openDelay: Long = 0, maxSessions: Int = 4) = UsenetSessionRegistry(
        opener = { token ->
            opened.incrementAndGet()
            if (openDelay > 0) Thread.sleep(openDelay)
            UsenetSession(token, "Movie.mkv", ByteVolume("Movie.mkv", payload), emptyList(), idleMs, trim = { trimmed += token })
        },
        clock = { now },
        maxSessions = maxSessions,
        reapPeriodMs = 0,
    )

    @Test
    fun `acquires of one token share one session, even when they race the first open`() {
        val sessions = registry(openDelay = 100)
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        val results = (1..4).map { pool.submit<UsenetSession> { start.await(); sessions.acquire(token(1)) } }
        start.countDown()
        val all = results.map { it.get(5, TimeUnit.SECONDS) }
        assertEquals(1, opened.get())
        assertTrue(all.all { it === all[0] })
        pool.shutdownNow()
    }

    @Test
    fun `at most four sessions stay open, the least recently used idle one closes first`() {
        val sessions = registry()
        val first = (1..4).map { n ->
            sessions.acquire(token(n)).also {
                now += 10
                sessions.release(it.token)
                now += 10
            }
        }
        sessions.acquire(token(1)).also { sessions.release(it.token) } // 1 used again, most recently
        now += 10
        sessions.acquire(token(5))
        assertEquals(4, sessions.openCount())
        assertTrue(first[1].closed, "token 2 was the least recently used")
        assertFalse(first[0].closed)
    }

    @Test
    fun `a reader of a session evicted while busy releases its own count, never the reopened session's`() {
        val sessions = registry(idleMs = 1_000, maxSessions = 1)
        val evicted = sessions.acquire(token(1))
        sessions.acquire(token(2)) // over the limit with both busy: token 1 is closed under its reader
        assertTrue(evicted.closed)
        val reopened = sessions.acquire(token(1))
        assertFalse(reopened === evicted)
        sessions.release(evicted.token) // the evicted reader finishes; the reopened session is still being read
        now += 1_000
        sessions.reap()
        assertFalse(reopened.closed, "the reopened session still has its reader")
        sessions.release(reopened.token)
        now += 1_000
        sessions.reap()
        assertTrue(reopened.closed)
    }

    @Test
    fun `the reaper closes sessions idle for their timeout, never one in use`() {
        val sessions = registry(idleMs = 1_000)
        val idle = sessions.acquire(token(1)).also { sessions.release(it.token) }
        val busy = sessions.acquire(token(2))
        now += 1_000
        sessions.reap()
        assertTrue(idle.closed)
        assertFalse(busy.closed)
    }

    @Test
    fun `idle sessions give their segment memory back when another one is acquired`() {
        val sessions = registry()
        sessions.acquire(token(1)).also { sessions.release(it.token) }
        val busy = sessions.acquire(token(2))
        sessions.acquire(token(3))
        assertEquals(setOf(token(1)), trimmed, "only the idle session is trimmed, never one being read")
        assertFalse(busy.closed)
    }

    @Test
    fun `a session the census failed refuses new readers`() {
        val sessions = registry()
        sessions.acquire(token(1)).fail(UsenetUnplayableException("broken"))
        assertThrows<UsenetUnplayableException> { sessions.acquire(token(1)) }
    }

    private fun spec(token: String, position: Long = 0, length: Long = C.LENGTH_UNSET.toLong()): DataSpec {
        val uri = mockk<Uri> { every { this@mockk.toString() } returns UsenetStreamRegistry.SCHEME_PREFIX + token }
        return DataSpec.Builder().setUri(uri).setPosition(position).setLength(length).build()
    }

    @Test
    fun `NntpDataSource opens with a known length, reads, and seeks by reopening at a position`() {
        val sessions = registry()
        val source = NntpDataSource(sessions)
        assertEquals(payload.size.toLong(), source.open(spec(token(7))))
        val head = ByteArray(1_000)
        var filled = 0
        while (filled < head.size) filled += source.read(head, filled, head.size - filled)
        assertArrayEquals(payload.copyOf(1_000), head)
        source.close()

        assertEquals(500L, source.open(spec(token(7), position = 80_000, length = 500)))
        val chunk = ByteArray(800)
        var got = 0
        while (true) {
            val n = source.read(chunk, got, chunk.size - got)
            if (n == C.RESULT_END_OF_INPUT) break
            got += n
        }
        assertEquals(500, got)
        assertArrayEquals(payload.copyOfRange(80_000, 80_500), chunk.copyOf(500))
        source.close()
        assertEquals(1, opened.get(), "seeking reuses the open session")
    }

    @Test
    fun `NntpDataSource refuses positions past the end and non-Usenet URIs`() {
        val source = NntpDataSource(registry())
        assertThrows<IOException> { source.open(spec(token(3), position = payload.size + 1L)) }
        source.close()
        val other = mockk<Uri> { every { this@mockk.toString() } returns "https://example.invalid/a.mkv" }
        assertThrows<IOException> { source.open(DataSpec.Builder().setUri(other).build()) }
    }

    @Test
    fun `lasting Usenet failures are not retried, transient ones are`() {
        assertTrue(UsenetLoadErrorPolicy.isLasting(ArchiveRefusedException("compressed")))
        assertTrue(UsenetLoadErrorPolicy.isLasting(IOException(ArticleNotFoundException("x", allProviders = true))))
        assertTrue(UsenetLoadErrorPolicy.isLasting(NntpException(NntpErrorKind.AUTH_FAILED, "bad login")))
        assertFalse(UsenetLoadErrorPolicy.isLasting(NntpException(NntpErrorKind.TIMEOUT, "slow")))
        assertFalse(UsenetLoadErrorPolicy.isLasting(ArticleNotFoundException("x", allProviders = false)))
    }
}
