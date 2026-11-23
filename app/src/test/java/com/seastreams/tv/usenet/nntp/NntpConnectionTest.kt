package com.seastreams.tv.usenet.nntp

import com.seastreams.tv.testutil.FakeNntpServer
import com.seastreams.tv.usenet.yenc.SegmentBufferPool
import com.seastreams.tv.usenet.yenc.YencDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.ServerSocket
import kotlin.random.Random

class NntpConnectionTest {
    private val server = FakeNntpServer()
    private val settings = NntpEngineSettings(dialTimeoutMs = 2_000, stallTimeoutMs = 2_000)

    @AfterEach
    fun tearDown() = server.close()

    private fun connect(endpoint: NntpEndpoint = server.endpoint()) = NntpConnection.connect(endpoint, settings)

    @Test
    fun `signs in with USER then PASS`() {
        connect().use { assertEquals(111, it.date().code) }
    }

    @Test
    fun `281 straight after USER is accepted, and a blank username skips AUTHINFO`() {
        server.userAnswer = "281 Ok"
        connect().use { assertEquals(111, it.date().code) }
        connect(server.endpoint(user = "", pass = "")).use { assertEquals(111, it.date().code) }
    }

    @Test
    fun `481 is a bad login`() {
        val error = assertThrows<NntpException> { connect(server.endpoint(pass = "wrong")) }
        assertEquals(NntpErrorKind.AUTH_FAILED, error.kind)
        assertEquals(481, error.code)
    }

    @Test
    fun `482 and 502 during sign-in are the connection limit only when worded so`() {
        server.passAnswer = "482 Too many connections"
        assertEquals(NntpErrorKind.CONNECTION_LIMIT, assertThrows<NntpException> { connect() }.kind)
        server.passAnswer = "502 Too many connections for user"
        assertEquals(NntpErrorKind.CONNECTION_LIMIT, assertThrows<NntpException> { connect() }.kind)
        server.passAnswer = "482 Authentication rejected"
        assertEquals(NntpErrorKind.AUTH_FAILED, assertThrows<NntpException> { connect() }.kind)
    }

    @Test
    fun `a Highwinds-style 502 for a refused login is a bad login and keeps the server's words`() {
        server.passAnswer = "502 Authentication Failed"
        val failed = assertThrows<NntpException> { connect() }
        assertEquals(NntpErrorKind.AUTH_FAILED to 502, failed.kind to failed.code)
        assertEquals("502 Authentication Failed", failed.reply)
        assertTrue(failed.message.orEmpty().contains("502 Authentication Failed"))
        server.passAnswer = "502 Service unavailable"
        assertEquals(NntpErrorKind.AUTH_FAILED, assertThrows<NntpException> { connect() }.kind)
    }

    @Test
    fun `a 502 greeting is the limit when worded so, else a bad login or refused connection`() {
        server.sessionLimit = 0
        assertEquals(NntpErrorKind.CONNECTION_LIMIT, assertThrows<NntpException> { connect() }.kind)
        server.limitAnswer = "502 Access denied for your IP"
        assertEquals(NntpErrorKind.AUTH_FAILED, assertThrows<NntpException> { connect() }.kind)
        server.limitAnswer = "502 Service unavailable"
        val refused = assertThrows<NntpException> { connect() }
        assertEquals(NntpErrorKind.CONNECTION, refused.kind)
        assertEquals("502 Service unavailable", refused.reply)
        server.sessionLimit = Int.MAX_VALUE
        server.greeting = "400 Service temporarily unavailable"
        assertEquals(NntpErrorKind.CONNECTION, assertThrows<NntpException> { connect() }.kind)
    }

    @Test
    fun `nothing listening is a connection failure`() {
        val closedPort = ServerSocket(0).use { it.localPort }
        val endpoint = NntpEndpoint(1, "Gone", "127.0.0.1", closedPort, false, "u", "p", 1, 1, 0, false)
        assertEquals(NntpErrorKind.CONNECTION, assertThrows<NntpException> { connect(endpoint) }.kind)
    }

    @Test
    fun `pipelined BODY and STAT answers come back in order`() {
        val data = Random(1).nextBytes(5_000)
        val file = server.addFile(data, partSize = 1_000)
        val decoder = YencDecoder(SegmentBufferPool())
        connect().use { connection ->
            file.segments.forEach { connection.sendBody(it.messageId) }
            connection.sendStat(file.segments[0].messageId)
            connection.sendStat("missing@test")
            connection.sendBody("missing@test")
            connection.flush()
            file.segments.forEachIndexed { index, segment ->
                assertEquals(222, connection.readStatus().code)
                val decoded = connection.readBody(decoder, segment.number, verifyCrc = true)
                assertArrayEquals(data.copyOfRange(index * 1_000, index * 1_000 + 1_000), decoded.toByteArray())
            }
            assertEquals(223, connection.readStatus().code)
            assertEquals(430, connection.readStatus().code)
            assertEquals(430, connection.readStatus().code)
        }
        assertTrue(server.peakUnanswered.get() > 1, "commands were in flight together")
    }

    @Test
    fun `the provider Test reports success, bad login, connection limit and unreachable`() = runBlocking<Unit> {
        val tester = NntpConnectionTester(Dispatchers.IO)
        assertTrue(tester.test(server.endpoint(), 2_000) is ProviderTestResult.Success)
        assertTrue(tester.test(server.endpoint(pass = "nope"), 2_000) is ProviderTestResult.BadLogin)
        server.sessionLimit = 0
        assertTrue(tester.test(server.endpoint(), 2_000) is ProviderTestResult.ConnectionLimit)
        val closedPort = ServerSocket(0).use { it.localPort }
        val gone = NntpEndpoint(1, "Gone", "127.0.0.1", closedPort, false, "u", "p", 1, 1, 0, false)
        val unreachable = tester.test(gone, 2_000)
        assertTrue(unreachable is ProviderTestResult.Unreachable)
        assertTrue(unreachable.message.startsWith("Couldn't reach Gone"))
    }

    @Test
    fun `the provider Test tells a 502 bad login from a 502 connection limit and quotes the server`() = runBlocking<Unit> {
        val tester = NntpConnectionTester(Dispatchers.IO)
        server.passAnswer = "502 Authentication Failed"
        val login = tester.test(server.endpoint(name = "Newshosting"), 2_000)
        assertEquals(ProviderTestResult.BadLogin("Newshosting", "502 Authentication Failed"), login)
        assertEquals("Newshosting rejected the username or password. Server said: 502 Authentication Failed", login.message)

        server.passAnswer = "502 Too many connections"
        val limit = tester.test(server.endpoint(name = "Newshosting"), 2_000)
        assertEquals(ProviderTestResult.ConnectionLimit("Newshosting", "502 Too many connections"), limit)
        assertTrue(limit.message.endsWith("Server said: 502 Too many connections"))

        server.passAnswer = null
        server.sessionLimit = 0
        server.limitAnswer = "502 Service unavailable"
        val refused = tester.test(server.endpoint(name = "Newshosting"), 2_000)
        assertTrue(refused is ProviderTestResult.Unreachable && refused.message.contains("502 Service unavailable"), refused.message)
    }

    @Test
    fun `a session that still refuses DATE after sign-in is a bad login, not a success`() = runBlocking<Unit> {
        val tester = NntpConnectionTester(Dispatchers.IO)
        server.dateAnswer = "480 Authentication required"
        assertEquals(ProviderTestResult.BadLogin("Fake", "480 Authentication required"), tester.test(server.endpoint(user = "", pass = ""), 2_000))
        server.dateAnswer = "500 Unknown command"
        assertTrue(tester.test(server.endpoint(), 2_000) is ProviderTestResult.Success)
    }
}
