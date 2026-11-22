package com.seastreams.tv.usenet.nntp

import com.seastreams.tv.usenet.yenc.DecodedSegment
import com.seastreams.tv.usenet.yenc.YencDecoder
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * A message ID goes on a command line, so it may hold no whitespace, control characters or angle brackets.
 * The NZB parser already guards it; [ProviderWorkerPool.submit] and the connection check it again.
 */
fun isValidMessageId(id: String): Boolean = id.isNotEmpty() && id.none { it <= ' ' || it == '<' || it == '>' }

/** Opens the raw socket for a provider; tests swap it to reach a local fake server. */
fun interface SocketOpener {
    fun open(endpoint: NntpEndpoint, dialTimeoutMs: Int): Socket
}

/**
 * ARCHITECTURE §10 sockets: a plain socket (`tcpNoDelay`, 512 KB receive buffer, dial timeout), wrapped by
 * `SSLSocketFactory.createSocket(plain, host, port, true)` with endpoint identification `HTTPS` when TLS is on.
 */
object DefaultSocketOpener : SocketOpener {
    private const val RECEIVE_BUFFER = 512 * 1024

    override fun open(endpoint: NntpEndpoint, dialTimeoutMs: Int): Socket {
        val plain = Socket()
        try {
            plain.tcpNoDelay = true
            plain.receiveBufferSize = RECEIVE_BUFFER
            plain.connect(InetSocketAddress(endpoint.host, endpoint.port), dialTimeoutMs)
            if (!endpoint.tls) return plain
            plain.soTimeout = dialTimeoutMs
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val secure = factory.createSocket(plain, endpoint.host, endpoint.port, true) as SSLSocket
            secure.sslParameters = secure.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            secure.startHandshake()
            return secure
        } catch (e: Exception) {
            runCatching { plain.close() }
            throw e
        }
    }
}

/**
 * One signed-in connection. Commands are queued with [sendBody] / [sendStat] / [sendDate] and written by
 * [flush], so several can be in flight (pipelining); answers come back strictly in order. Not thread-safe:
 * one worker owns it. [close] may be called from any thread to unblock a read.
 */
class NntpConnection private constructor(
    val endpoint: NntpEndpoint,
    private val socket: Socket,
    private val reader: NntpReader,
    private val output: OutputStream,
) : Closeable {
    @Volatile
    var closed = false
        private set

    /** Last time a command was answered, for idle TTL / keep-alive ([monotonicMillis] scale). */
    @Volatile
    var lastUsedAt: Long = 0

    fun sendBody(messageId: String) = send("BODY <${checkMessageId(messageId)}>")

    fun sendStat(messageId: String) = send("STAT <${checkMessageId(messageId)}>")

    fun sendDate() = send("DATE")

    fun flush() = output.flush()

    fun readStatus(): NntpStatus = reader.readStatus()

    /** Decodes the article that follows a `222` answer. */
    fun readBody(decoder: YencDecoder, expectedPart: Int?, verifyCrc: Boolean): DecodedSegment =
        decoder.decode(reader, expectedPart, verifyCrc)

    /** Drops the article that follows a `222` answer (its fetch was cancelled while in flight). */
    fun skipBody() = reader.skipMultiline()

    /** A synchronous `DATE` round trip (keep-alive and the provider `Test`). */
    fun date(): NntpStatus {
        sendDate()
        flush()
        return readStatus()
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { socket.close() }
    }

    private fun send(command: String) {
        output.write(command.toByteArray(Charsets.ISO_8859_1))
        output.write(CRLF)
    }

    companion object {
        private val CRLF = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte())

        private fun checkMessageId(id: String): String {
            require(isValidMessageId(id)) { "Invalid message ID" }
            return id
        }

        /**
         * Dials, reads the greeting (`200`/`201`) and signs in: `AUTHINFO USER` then `PASS` on `381` (a `281`
         * straight after `USER` is accepted too). Failures are classified ([classifyStatus]): a bad login is
         * [NntpErrorKind.AUTH_FAILED], a reply *worded* as "too many connections" is [NntpErrorKind.CONNECTION_LIMIT]
         * (a bare 482/502 is not: Highwinds uses 502 for refused logins). `MODE READER` is not sent: reader-only
         * servers (Highwinds, Omicron, Eweka …) accept AUTHINFO straight after the greeting, and some answer
         * `MODE READER` before sign-in with 480/502.
         */
        fun connect(
            endpoint: NntpEndpoint,
            settings: NntpEngineSettings,
            opener: SocketOpener = DefaultSocketOpener,
            clock: () -> Long = monotonicMillis,
            onBytes: (Int) -> Unit = {},
        ): NntpConnection {
            val socket = try {
                opener.open(endpoint, settings.dialTimeoutMs)
            } catch (e: IOException) {
                throw dialFailure(endpoint, e)
            } catch (e: RuntimeException) {
                throw dialFailure(endpoint, IOException(e.message, e))
            }
            try {
                socket.soTimeout = settings.dialTimeoutMs
                val connection = NntpConnection(
                    endpoint,
                    socket,
                    NntpReader(socket.getInputStream(), onBytes = onBytes),
                    BufferedOutputStream(socket.getOutputStream(), 4 * 1024),
                )
                connection.signIn()
                socket.soTimeout = settings.stallTimeoutMs
                connection.lastUsedAt = clock()
                return connection
            } catch (e: NntpException) {
                runCatching { socket.close() }
                throw e
            } catch (e: IOException) {
                runCatching { socket.close() }
                throw dialFailure(endpoint, e)
            } catch (e: RuntimeException) {
                runCatching { socket.close() }
                throw dialFailure(endpoint, IOException(e.message, e))
            }
        }

        private fun NntpConnection.signIn() {
            val greeting = readStatus()
            if (greeting.code != 200 && greeting.code != 201) throw refused(greeting, duringAuth = false)
            if (endpoint.username.isEmpty()) return
            send("AUTHINFO USER ${endpoint.username}")
            flush()
            var answer = readStatus()
            if (answer.code == 381) {
                send("AUTHINFO PASS ${endpoint.password}")
                flush()
                answer = readStatus()
            }
            if (answer.code != 281) throw refused(answer, duringAuth = true)
        }

        private fun refused(status: NntpStatus, duringAuth: Boolean): NntpException {
            val kind = classifyStatus(status.code, status.text, duringAuth).let {
                if (it == NntpErrorKind.ARTICLE_NOT_FOUND) NntpErrorKind.PROTOCOL else it
            }
            val reply = status.describe()
            val message = when (kind) {
                NntpErrorKind.AUTH_FAILED -> "The news server rejected the sign-in ($reply)"
                NntpErrorKind.CONNECTION_LIMIT -> "The news server is at its connection limit ($reply)"
                else -> "The news server refused the connection ($reply)"
            }
            return NntpException(kind, message, status.code, reply = reply)
        }

        private fun dialFailure(endpoint: NntpEndpoint, e: IOException): NntpException = when (e) {
            is NntpException -> e
            is SocketTimeoutException -> NntpException(NntpErrorKind.TIMEOUT, "${endpoint.host} didn't answer in time", cause = e)
            is UnknownHostException -> NntpException(NntpErrorKind.CONNECTION, "${endpoint.host} couldn't be found", cause = e)
            is SSLException -> NntpException(NntpErrorKind.CONNECTION, "The secure connection to ${endpoint.host} failed", cause = e)
            else -> NntpException(NntpErrorKind.CONNECTION, "Couldn't connect to ${endpoint.host}:${endpoint.port}", cause = e)
        }
    }
}
