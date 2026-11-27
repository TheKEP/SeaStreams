package com.seastreams.tv.core.webserver

import com.seastreams.tv.testutil.TestHttpServer
import io.ktor.server.routing.post
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.Socket
import java.net.URI

/**
 * The portal's body cap holds for chunked uploads, which declare no `Content-Length` (so [acceptsJsonPost]'s declared
 * size check can't see them): the body is refused while it is read, never buffered whole first.
 */
class PortalBodyLimitTest {

    private val server = TestHttpServer {
        post("/json") { call.mutate<TmdbKeyRequest> { "saved ${it.apiKey.length}" } }
        post("/import") { call.guarded { "read ${call.receiveBoundedText(LIMIT).length}" } }
        post("/restore") { call.guarded { "read ${call.receiveBoundedText(LIMIT, "That file is too large").length}" } }
    }

    @AfterEach
    fun tearDown() = server.close()

    @Test
    fun `a chunked JSON body over the cap is refused`() {
        val oversized = """{"apiKey":"${"a".repeat(MAX_BODY_CHARS)}"}"""
        val (status, body) = chunkedPost("/json", oversized)
        assertEquals(400, status)
        assertTrue(body.contains("Request too large"), body)
    }

    @Test
    fun `a chunked JSON body within the cap is accepted`() {
        val (status, body) = chunkedPost("/json", """{"apiKey":"abc"}""")
        assertEquals(200, status)
        assertTrue(body.contains("saved 3"), body)
    }

    @Test
    fun `raw text bodies stop at their own cap with their own message`() {
        assertEquals(200, chunkedPost("/import", "x".repeat(LIMIT)).first)
        val (status, body) = chunkedPost("/import", "x".repeat(LIMIT + 1))
        assertEquals(400, status)
        assertTrue(body.contains("Request too large"), body)
        val restore = chunkedPost("/restore", "x".repeat(LIMIT * 3))
        assertEquals(400, restore.first)
        assertTrue(restore.second.contains("That file is too large"), restore.second)
    }

    /** POSTs [body] as `Transfer-Encoding: chunked` JSON, with no `Content-Length`; returns status and response text. */
    private fun chunkedPost(path: String, body: String): Pair<Int, String> {
        val uri = URI(server.url(path))
        return Socket(uri.host, uri.port).use { socket ->
            val out = socket.getOutputStream()
            out.write(
                ("POST $path HTTP/1.1\r\nHost: 127.0.0.1:${uri.port}\r\nContent-Type: application/json\r\n" +
                    "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n").toByteArray(),
            )
            // The server may answer and close before the whole oversized body is sent.
            runCatching {
                body.toByteArray().asList().chunked(CHUNK).forEach { piece ->
                    out.write("${Integer.toHexString(piece.size)}\r\n".toByteArray())
                    out.write(piece.toByteArray())
                    out.write("\r\n".toByteArray())
                }
                out.write("0\r\n\r\n".toByteArray())
                out.flush()
            }
            val response = socket.getInputStream().bufferedReader().readText()
            val status = response.lineSequence().first().split(' ')[1].toInt()
            status to response.substringAfter("\r\n\r\n")
        }
    }

    private companion object {
        const val LIMIT = 1_000
        const val CHUNK = 4_096
    }
}
