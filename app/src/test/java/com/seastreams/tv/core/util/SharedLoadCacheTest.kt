package com.seastreams.tv.core.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

class SharedLoadCacheTest {

    private var now = 0L

    /** Like the app scope: a failed load must not fail its siblings (or the test). */
    private fun TestScope.supervised() = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext.job))

    @Test
    fun `concurrent callers share one load`() = runTest {
        val cache = SharedLoadCache<String, Int>(supervised(), ttlMillis = 1_000, nowMillis = { now })
        val gate = CompletableDeferred<Unit>()
        var loads = 0

        val first = async { cache.get("k") { loads++; gate.await(); 42 } }
        val second = async { cache.get("k") { loads++; 7 } }
        testScheduler.runCurrent()
        gate.complete(Unit)

        assertEquals(42, first.await())
        assertEquals(42, second.await())
        assertEquals(1, loads)
    }

    @Test
    fun `a failed load is evicted and retried`() = runTest {
        val cache = SharedLoadCache<String, Int>(supervised(), ttlMillis = 1_000, nowMillis = { now })

        assertThrows<IOException> { cache.get("k") { throw IOException("offline") } }

        assertEquals(5, cache.get("k") { 5 })
    }

    @Test
    fun `entries expire after the ttl`() = runTest {
        val cache = SharedLoadCache<String, Int>(supervised(), ttlMillis = 1_000, nowMillis = { now })
        var loads = 0

        cache.get("k") { ++loads }
        now = 999
        assertEquals(1, cache.get("k") { ++loads })
        now = 1_000
        assertEquals(2, cache.get("k") { ++loads })
    }

    @Test
    fun `take hands over the cached value once`() = runTest {
        val cache = SharedLoadCache<String, Int>(supervised(), ttlMillis = 1_000, nowMillis = { now })
        var loads = 0

        cache.start("k") { ++loads }
        assertEquals(1, cache.take("k") { ++loads })
        assertEquals(2, cache.take("k") { ++loads })
    }

    @Test
    fun `a load keeps running when its caller stops waiting`() = runTest {
        val cache = SharedLoadCache<String, Int>(supervised(), ttlMillis = 1_000, nowMillis = { now })
        val gate = CompletableDeferred<Unit>()
        var loads = 0

        val waiter = async { cache.get("k") { loads++; gate.await(); 9 } }
        testScheduler.runCurrent()
        waiter.cancel()
        gate.complete(Unit)

        assertEquals(9, cache.get("k") { loads++; 0 })
        assertEquals(1, loads)
    }
}
