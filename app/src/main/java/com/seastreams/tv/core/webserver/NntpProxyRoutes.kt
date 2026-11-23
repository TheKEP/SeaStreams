package com.seastreams.tv.core.webserver

import com.seastreams.tv.core.util.IoDispatcher
import com.seastreams.tv.usenet.archive.UsenetUnplayableException
import com.seastreams.tv.usenet.session.UsenetSession
import com.seastreams.tv.usenet.session.UsenetSessionRegistry
import com.seastreams.tv.usenet.session.UsenetStreamRegistry
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runInterruptible
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The loopback Usenet proxy for external players (PRD §12): `GET/HEAD /nntp/{token}/{name}` on the portal server
 * serves an opened release with single-range `206` / `416` answers. Only loopback clients are served (any other
 * client gets `403`), so a LAN device can never pull a stream through the TV. Unknown tokens are `404`; a release that
 * can't stream is `502`. Reads share the token's session with the built-in player.
 */
@Singleton
class NntpProxy @Inject constructor(
    private val sessions: UsenetSessionRegistry,
    private val streams: UsenetStreamRegistry,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    suspend fun serve(call: ApplicationCall, head: Boolean) {
        if (!isLoopbackAddress(call.request.local.remoteAddress)) {
            return call.respondText("Usenet streams are only served to apps on this TV", status = HttpStatusCode.Forbidden)
        }
        val token = call.parameters["token"]?.takeIf(UsenetStreamRegistry::isToken)
        if (token == null || (streams.get(token) == null && !sessions.isOpen(token))) {
            return call.respondText("Unknown stream", status = HttpStatusCode.NotFound)
        }
        val session = try {
            runInterruptible(io) { sessions.acquire(token) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: UsenetUnplayableException) {
            return call.respondText(e.message.orEmpty(), status = HttpStatusCode.BadGateway)
        } catch (e: IOException) {
            return call.respondText("The Usenet stream couldn't be opened", status = HttpStatusCode.BadGateway)
        }
        try {
            val size = session.size
            val range = ByteRange.parse(call.request.headers[HttpHeaders.Range], size)
            call.response.header(HttpHeaders.AcceptRanges, "bytes")
            val (status, start, length) = when (range) {
                ByteRange.Unsatisfiable -> {
                    call.response.header(HttpHeaders.ContentRange, "bytes */$size")
                    return call.respondText("", status = HttpStatusCode.RequestedRangeNotSatisfiable)
                }
                ByteRange.Full -> Triple(HttpStatusCode.OK, 0L, size)
                is ByteRange.Partial -> {
                    call.response.header(HttpHeaders.ContentRange, "bytes ${range.start}-${range.endInclusive}/$size")
                    Triple(HttpStatusCode.PartialContent, range.start, range.length)
                }
            }
            val type = ContentType.parse(session.container.mimeType)
            if (head) return call.respond(Headers(status, type, length))
            // `respond` returns once the body is written (or the player hung up), so the release below covers both.
            call.respond(Body(status, type, length) { channel -> copy(session, start, length, channel) })
        } finally {
            sessions.release(token)
        }
    }

    private suspend fun copy(session: UsenetSession, start: Long, length: Long, channel: ByteWriteChannel) {
        val buffer = ByteArray(CHUNK)
        var position = start
        val end = start + length
        while (position < end) {
            val want = minOf(CHUNK.toLong(), end - position).toInt()
            val n = runInterruptible(io) { session.read(position, buffer, 0, want) }
            if (n <= 0) break
            channel.writeFully(buffer, 0, n)
            position += n
        }
        channel.flush()
    }

    private class Headers(override val status: HttpStatusCode, override val contentType: ContentType, override val contentLength: Long) :
        OutgoingContent.NoContent()

    private class Body(
        override val status: HttpStatusCode,
        override val contentType: ContentType,
        override val contentLength: Long,
        private val write: suspend (ByteWriteChannel) -> Unit,
    ) : OutgoingContent.WriteChannelContent() {
        override suspend fun writeTo(channel: ByteWriteChannel) = write(channel)
    }

    companion object {
        private const val CHUNK = 256 * 1024

        /** IPv4 `127/8`, IPv6 `::1` and its IPv4-mapped forms. Literals only, never a DNS lookup. */
        fun isLoopbackAddress(address: String): Boolean {
            val bare = address.trim().removePrefix("[").removeSuffix("]").substringBefore('%').lowercase()
            val v4 = bare.removePrefix("::ffff:")
            val octets = v4.split('.')
            if (octets.size == 4 && octets.all { o -> o.toIntOrNull()?.let { it in 0..255 } == true }) return octets[0] == "127"
            return bare == "::1" || bare == "0:0:0:0:0:0:0:1"
        }
    }
}

/** `GET` / `HEAD /nntp/{token}/{name}` (the name is cosmetic: players show it and guess the type from it). */
internal fun Route.nntpProxyRoutes(proxy: NntpProxy) {
    get("/nntp/{token}/{name}") { proxy.serve(call, head = false) }
    head("/nntp/{token}/{name}") { proxy.serve(call, head = true) }
}
