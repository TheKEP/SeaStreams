package com.seastreams.tv.core.webserver

import com.seastreams.tv.data.usenet.newznab.NzbRequest
import com.seastreams.tv.usenet.archive.ByteVolume
import com.seastreams.tv.usenet.session.UsenetGrab
import com.seastreams.tv.usenet.session.UsenetSession
import com.seastreams.tv.usenet.session.UsenetSessionRegistry
import com.seastreams.tv.usenet.session.UsenetStreamRegistry
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.random.Random

class NntpProxyTest {
    private val payload = Random(9).nextBytes(300_000)
    private val streams = UsenetStreamRegistry()
    private val sessions = UsenetSessionRegistry(
        opener = { token -> UsenetSession(token, "Movie Name.mkv", ByteVolume("Movie Name.mkv", payload), emptyList(), 0) },
        clock = { 0L },
        maxSessions = 4,
        reapPeriodMs = 0,
    )
    private val proxy = NntpProxy(sessions, streams, Dispatchers.IO)
    private val portal = LocalPortalServer(mockk<PortalController>(relaxed = true), mockk<PortalUsenetController>(relaxed = true), proxy)
    private val http = OkHttpClient()
    private val token = checkNotNull(
        UsenetStreamRegistry.tokenOf(streams.register(UsenetGrab.FromAddon("https://addon", NzbRequest("https://x/a.nzb", "k"), "Movie", emptyList()))),
    )

    @AfterEach
    fun tearDown() {
        portal.stop()
        sessions.close()
    }

    private fun get(base: String, path: String, range: String? = null, method: String = "GET") =
        http.newCall(Request.Builder().url(base + path).apply { range?.let { header("Range", it) } }.method(method, null).build()).execute()

    private fun local(): String = "http://127.0.0.1:" + checkNotNull(portal.start(port = 0, host = "127.0.0.1"))

    @Test
    fun `a full GET serves the release with its length and type`() {
        get(local(), "/nntp/$token/Movie%20Name.mkv").use { response ->
            assertEquals(200, response.code)
            assertEquals("bytes", response.header("Accept-Ranges"))
            assertEquals("video/x-matroska", response.header("Content-Type"))
            assertArrayEquals(payload, response.body.bytes())
        }
    }

    @Test
    fun `single ranges answer 206 with Content-Range, past the end 416`() {
        val base = local()
        get(base, "/nntp/$token/a.mkv", "bytes=1000-1999").use { response ->
            assertEquals(206, response.code)
            assertEquals("bytes 1000-1999/300000", response.header("Content-Range"))
            assertArrayEquals(payload.copyOfRange(1_000, 2_000), response.body.bytes())
        }
        get(base, "/nntp/$token/a.mkv", "bytes=-500").use { response ->
            assertEquals(206, response.code)
            assertArrayEquals(payload.copyOfRange(299_500, 300_000), response.body.bytes())
        }
        get(base, "/nntp/$token/a.mkv", "bytes=300000-").use { response ->
            assertEquals(416, response.code)
            assertEquals("bytes */300000", response.header("Content-Range"))
        }
    }

    @Test
    fun `HEAD answers headers only`() {
        get(local(), "/nntp/$token/a.mkv", "bytes=0-99", method = "HEAD").use { response ->
            assertEquals(206, response.code)
            assertEquals("100", response.header("Content-Length"))
        }
    }

    @Test
    fun `unknown tokens are 404`() {
        get(local(), "/nntp/${"0".repeat(32)}/a.mkv").use { assertEquals(404, it.code) }
        get(local(), "/nntp/not-a-token/a.mkv").use { assertEquals(404, it.code) }
    }

    @Test
    fun `LAN clients get 403`() {
        val lan = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
        assumeTrue(lan != null, "no LAN address on this machine")
        val port = checkNotNull(portal.start(port = 0, host = "0.0.0.0"))
        get("http://${checkNotNull(lan).hostAddress}:$port", "/nntp/$token/a.mkv").use { assertEquals(403, it.code) }
    }

    @Test
    fun `loopback addresses are recognised without DNS`() {
        assertTrue(NntpProxy.isLoopbackAddress("127.0.0.1"))
        assertTrue(NntpProxy.isLoopbackAddress("127.4.5.6"))
        assertTrue(NntpProxy.isLoopbackAddress("::1"))
        assertTrue(NntpProxy.isLoopbackAddress("0:0:0:0:0:0:0:1"))
        assertTrue(NntpProxy.isLoopbackAddress("::ffff:127.0.0.1"))
        assertFalse(NntpProxy.isLoopbackAddress("192.168.1.20"))
        assertFalse(NntpProxy.isLoopbackAddress("localhost.evil.example"))
        assertFalse(NntpProxy.isLoopbackAddress("fe80::1"))
    }

    @Test
    fun `byte ranges parse like RFC 9110 single ranges`() {
        assertEquals(ByteRange.Full, ByteRange.parse(null, 100))
        assertEquals(ByteRange.Partial(0, 99), ByteRange.parse("bytes=0-", 100))
        assertEquals(ByteRange.Partial(10, 99), ByteRange.parse("bytes=10-500", 100))
        assertEquals(ByteRange.Partial(90, 99), ByteRange.parse("bytes=-10", 100))
        assertEquals(ByteRange.Partial(0, 99), ByteRange.parse("bytes=-1000", 100))
        assertEquals(ByteRange.Unsatisfiable, ByteRange.parse("bytes=100-", 100))
        assertEquals(ByteRange.Unsatisfiable, ByteRange.parse("bytes=-0", 100))
        assertEquals(ByteRange.Full, ByteRange.parse("bytes=0-1,5-6", 100))
        assertEquals(ByteRange.Full, ByteRange.parse("items=0-1", 100))
        assertEquals(ByteRange.Full, ByteRange.parse("bytes=9-3", 100))
    }

    @Test
    fun `a lease keeps the server running across onStop, and its release stops it`() {
        local()
        portal.requestStart()
        val lease = portal.acquireLease()
        // MainActivity.onStop while an external player streams.
        if (portal.leased) portal.releaseUiClaim() else portal.requestStop()
        Thread.sleep(300)
        assertTrue(portal.status.value is PortalStatus.Running)
        assertTrue(portal.leased)
        lease.close()
        val deadline = System.currentTimeMillis() + 5_000
        while (portal.status.value is PortalStatus.Running && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(PortalStatus.Stopped, portal.status.value)
    }

    @Test
    fun `a stop without a lease still stops the server`() {
        local()
        portal.requestStop()
        val deadline = System.currentTimeMillis() + 5_000
        while (portal.status.value is PortalStatus.Running && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(PortalStatus.Stopped, portal.status.value)
    }
}
