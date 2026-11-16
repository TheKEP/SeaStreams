package com.seastreams.tv.core.network

import com.seastreams.tv.testutil.TestHttpServer
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class RateLimitRetryInterceptorTest {

    /** Number of 429s the server returns before succeeding (Int.MAX_VALUE = always 429). */
    private val throttledResponses = AtomicInteger(0)
    private val retryAfterHeader = AtomicReference<String?>(null)
    private val delays = CopyOnWriteArrayList<Long>()
    private val recordingSleeper = Sleeper { delays += it }

    private lateinit var server: TestHttpServer

    @BeforeEach
    fun setUp() {
        server = TestHttpServer {
            get("/limited") {
                if (throttledResponses.getAndDecrement() > 0) {
                    retryAfterHeader.get()?.let { call.response.header("Retry-After", it) }
                    call.respondText("slow down", status = HttpStatusCode.TooManyRequests)
                } else {
                    call.respondText("ok")
                }
            }
            get("/server-error") { call.respondText("boom", status = HttpStatusCode.ServiceUnavailable) }
        }
    }

    @AfterEach
    fun tearDown() = server.close()

    @Test
    fun `retries 429 with exponential backoff and returns the eventual success`() {
        throttledResponses.set(2)

        val (code, body) = execute(clientWith(RateLimitRetryInterceptor(sleeper = recordingSleeper)), "/limited")

        assertEquals(200, code)
        assertEquals("ok", body)
        assertEquals(3, server.hits("/limited"))
        assertEquals(listOf(1_000L, 2_000L), delays)
    }

    @Test
    fun `gives up after maxRetries and surfaces the final 429`() {
        throttledResponses.set(Int.MAX_VALUE)

        val (code, _) = execute(clientWith(RateLimitRetryInterceptor(maxRetries = 3, sleeper = recordingSleeper)), "/limited")

        assertEquals(429, code)
        assertEquals(4, server.hits("/limited"), "1 initial attempt + 3 retries")
        assertEquals(listOf(1_000L, 2_000L, 4_000L), delays)
    }

    @Test
    fun `backoff is capped at maxDelayMs`() {
        throttledResponses.set(Int.MAX_VALUE)
        val interceptor = RateLimitRetryInterceptor(
            maxRetries = 5,
            baseDelayMs = 1_000,
            maxDelayMs = 4_000,
            sleeper = recordingSleeper,
        )

        execute(clientWith(interceptor), "/limited")

        assertEquals(listOf(1_000L, 2_000L, 4_000L, 4_000L, 4_000L), delays)
    }

    @Test
    fun `numeric Retry-After header overrides the computed backoff`() {
        throttledResponses.set(1)
        retryAfterHeader.set("7")

        val (code, _) = execute(clientWith(RateLimitRetryInterceptor(sleeper = recordingSleeper)), "/limited")

        assertEquals(200, code)
        assertEquals(listOf(7_000L), delays)
    }

    @Test
    fun `Retry-After is capped so a hostile header cannot stall the app`() {
        throttledResponses.set(1)
        retryAfterHeader.set("3600")

        execute(clientWith(RateLimitRetryInterceptor(maxDelayMs = 16_000, sleeper = recordingSleeper)), "/limited")

        assertEquals(listOf(16_000L), delays)
    }

    @Test
    fun `non numeric Retry-After falls back to exponential backoff`() {
        throttledResponses.set(1)
        retryAfterHeader.set("Wed, 21 Oct 2026 07:28:00 GMT")

        execute(clientWith(RateLimitRetryInterceptor(sleeper = recordingSleeper)), "/limited")

        assertEquals(listOf(1_000L), delays)
    }

    @Test
    fun `other error statuses are not retried`() {
        val (code, _) = execute(clientWith(RateLimitRetryInterceptor(sleeper = recordingSleeper)), "/server-error")

        assertEquals(503, code)
        assertEquals(1, server.hits("/server-error"))
        assertTrue(delays.isEmpty())
    }

    @Test
    fun `zero retries returns the 429 immediately`() {
        throttledResponses.set(1)

        val (code, _) = execute(clientWith(RateLimitRetryInterceptor(maxRetries = 0, sleeper = recordingSleeper)), "/limited")

        assertEquals(429, code)
        assertEquals(1, server.hits("/limited"))
        assertTrue(delays.isEmpty())
    }

    @Test
    fun `a call canceled during backoff stops retrying`() {
        throttledResponses.set(Int.MAX_VALUE)
        val callRef = AtomicReference<Call>()
        val cancelingSleeper = Sleeper { callRef.get().cancel() }
        val client = clientWith(RateLimitRetryInterceptor(sleeper = cancelingSleeper))
        val call = client.newCall(Request.Builder().url(server.url("/limited")).build())
        callRef.set(call)

        assertThrows<IOException> { call.execute() }
        assertEquals(1, server.hits("/limited"))
    }

    @Test
    fun `constructor rejects invalid configuration`() {
        assertThrows<IllegalArgumentException> { RateLimitRetryInterceptor(maxRetries = -1) }
        assertThrows<IllegalArgumentException> { RateLimitRetryInterceptor(baseDelayMs = 0) }
        assertThrows<IllegalArgumentException> { RateLimitRetryInterceptor(baseDelayMs = 2_000, maxDelayMs = 1_000) }
    }

    private fun clientWith(interceptor: RateLimitRetryInterceptor): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(interceptor).build()

    private fun execute(client: OkHttpClient, path: String): Pair<Int, String> =
        client.newCall(Request.Builder().url(server.url(path)).build()).execute().use { it.code to it.body.string() }
}
