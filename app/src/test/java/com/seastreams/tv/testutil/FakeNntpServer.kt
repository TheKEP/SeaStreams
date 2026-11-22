package com.seastreams.tv.testutil

import com.seastreams.tv.usenet.nntp.NntpEndpoint
import com.seastreams.tv.usenet.nzb.NzbFile
import com.seastreams.tv.usenet.nzb.NzbSegment
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * A real NNTP server on a localhost socket for engine tests. It serves yEnc articles ([addFile] encodes them),
 * answers `430` for unknown IDs, and can be told to refuse sign-ins (`481`/`482`/`502`), cap concurrent sessions
 * ([sessionLimit] → `502 Too many connections`), stall on chosen articles, or send CRC-corrupt bodies.
 * Commands of a session are read on one thread and answered in order on another, so [peakUnanswered] measures
 * how deep the client really pipelines.
 */
class FakeNntpServer : Closeable {
    private val server = ServerSocket(0, 100, InetAddress.getLoopbackAddress())
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val articles = ConcurrentHashMap<String, ByteArray>()

    val port: Int get() = server.localPort

    @Volatile var username = "user"
    @Volatile var password = "pass"
    @Volatile var greeting = "200 fake news ready"

    /** Overrides the answer to `AUTHINFO USER` / `AUTHINFO PASS` (e.g. `"481 Authentication rejected"`). */
    @Volatile var userAnswer: String? = null
    @Volatile var passAnswer: String? = null

    /** Overrides the answer to `DATE` (e.g. `"480 Authentication required"` for a server that needs a login). */
    @Volatile var dateAnswer: String? = null
    @Volatile var sessionLimit = Int.MAX_VALUE
    @Volatile var limitAnswer = "502 Too many connections"
    @Volatile var answerDelayMs = 0L

    /** IDs answered with silence (the client's stall timeout fires); [stallOnce] stalls only the first request. */
    val stalled: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val stallOnce: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** IDs whose body is sent with one data byte flipped (the trailer CRC then fails). */
    val corrupt: MutableSet<String> = ConcurrentHashMap.newKeySet()

    val accepted = AtomicInteger()
    val active = AtomicInteger()
    val peakActive = AtomicInteger()
    val peakUnanswered = AtomicInteger()
    val bodyRequests = ConcurrentHashMap<String, AtomicInteger>()
    val dateCommands = AtomicInteger()
    val bytesSent = AtomicLong()

    init {
        Thread({ acceptLoop() }, "fake-nntp-accept").apply { isDaemon = true }.start()
    }

    fun endpoint(
        name: String = "Fake",
        maxConnections: Int = 4,
        pipelineDepth: Int = 2,
        priority: Int = 0,
        isBackup: Boolean = false,
        id: Long = port.toLong(),
        user: String = username,
        pass: String = password,
    ) = NntpEndpoint(id, name, "127.0.0.1", port, false, user, pass, maxConnections, pipelineDepth, priority, isBackup)

    fun put(messageId: String, body: ByteArray) {
        articles[messageId] = body
    }

    fun remove(messageId: String) {
        articles.remove(messageId)
    }

    /** Splits [data] into posts of [partSize] (or the explicit [partSizes]) and serves them; returns the NZB file. */
    fun addFile(
        data: ByteArray,
        partSize: Int = 1_000,
        name: String = "video.mkv",
        idPrefix: String = name,
        partSizes: List<Int>? = null,
    ): NzbFile {
        val sizes = partSizes ?: List((data.size + partSize - 1) / partSize) { minOf(partSize, data.size - it * partSize) }
        require(sizes.sum() == data.size)
        val segments = ArrayList<NzbSegment>()
        var from = 0
        sizes.forEachIndexed { index, size ->
            val number = index + 1
            val id = "$idPrefix.$number@test"
            val body = YencTestEncoder.encode(data, from, from + size, name = name, part = number, total = sizes.size)
            put(id, body)
            segments += NzbSegment(number, body.size.toLong(), id)
            from += size
        }
        return NzbFile("\"$name\" yEnc (1/${sizes.size})", "poster", listOf("alt.binaries.test"), segments)
    }

    fun bodyCount(messageId: String): Int = bodyRequests[messageId]?.get() ?: 0

    override fun close() {
        runCatching { server.close() }
        clients.forEach { runCatching { it.close() } }
    }

    private fun acceptLoop() {
        while (!server.isClosed) {
            val socket = try {
                server.accept()
            } catch (e: java.io.IOException) {
                return
            }
            accepted.incrementAndGet()
            clients += socket
            Thread({ session(socket) }, "fake-nntp-session").apply { isDaemon = true }.start()
        }
    }

    private fun session(socket: Socket) {
        val now = active.incrementAndGet()
        peakActive.accumulateAndGet(now) { a, b -> maxOf(a, b) }
        val output = socket.getOutputStream()
        try {
            if (now > sessionLimit) {
                send(output, limitAnswer)
                return
            }
            send(output, greeting)
            if (!greeting.startsWith("20")) return
            val commands = LinkedBlockingQueue<String>()
            val received = AtomicInteger()
            val answered = AtomicInteger()
            val reader = Thread({
                val input = BufferedInputStream(socket.getInputStream())
                while (true) {
                    val line = readLine(input) ?: break
                    received.incrementAndGet()
                    peakUnanswered.accumulateAndGet(received.get() - answered.get()) { a, b -> maxOf(a, b) }
                    commands.put(line)
                }
                commands.put(EOF)
            }, "fake-nntp-reader").apply { isDaemon = true }
            reader.start()
            while (true) {
                val command = commands.take()
                if (command == EOF) return
                if (answerDelayMs > 0) Thread.sleep(answerDelayMs)
                // Counted before the answer is written: the client may send its next command as soon as it reads it.
                answered.incrementAndGet()
                if (!answer(command, output)) return
            }
        } catch (e: Exception) {
            // Client went away.
        } finally {
            active.decrementAndGet()
            clients -= socket
            runCatching { socket.close() }
        }
    }

    /** `false` ends the session. */
    private fun answer(command: String, output: OutputStream): Boolean {
        val upper = command.uppercase()
        when {
            upper.startsWith("AUTHINFO USER") -> {
                val answer = userAnswer ?: if (command.substringAfter("USER ") == username) "381 Password required" else "481 Authentication rejected"
                send(output, answer)
            }
            upper.startsWith("AUTHINFO PASS") -> {
                val answer = passAnswer ?: if (command.substringAfter("PASS ") == password) "281 Authentication accepted" else "481 Authentication rejected"
                send(output, answer)
            }
            upper == "DATE" -> {
                dateCommands.incrementAndGet()
                send(output, dateAnswer ?: "111 20261122120000")
            }
            upper == "QUIT" -> {
                send(output, "205 bye")
                return false
            }
            upper.startsWith("STAT ") -> {
                val id = command.substringAfter('<').substringBefore('>')
                send(output, if (articles.containsKey(id)) "223 0 <$id>" else "430 No such article")
            }
            upper.startsWith("BODY ") -> {
                val id = command.substringAfter('<').substringBefore('>')
                bodyRequests.getOrPut(id) { AtomicInteger() }.incrementAndGet()
                if (id in stalled || stallOnce.remove(id)) {
                    Thread.sleep(60_000)
                    return false
                }
                val body = articles[id] ?: return true.also { send(output, "430 No such article") }
                val sent = if (id in corrupt) corrupted(body) else body
                val buffer = ByteArrayOutputStream(sent.size + 64)
                buffer.write("222 0 <$id>\r\n".toByteArray())
                buffer.write(sent)
                buffer.write(".\r\n".toByteArray())
                output.write(buffer.toByteArray())
                output.flush()
                bytesSent.addAndGet(buffer.size().toLong())
            }
            else -> send(output, "500 What?")
        }
        return true
    }

    /** Flips one byte on the first data line (after `=ybegin`/`=ypart`), keeping the trailer's CRC. */
    private fun corrupted(body: ByteArray): ByteArray {
        val copy = body.copyOf()
        var lineStart = 0
        var lines = 0
        while (lineStart < copy.size) {
            val end = (lineStart until copy.size).first { copy[it] == '\n'.code.toByte() }
            val isMeta = copy[lineStart] == '='.code.toByte() && copy.getOrNull(lineStart + 1) == 'y'.code.toByte()
            if (!isMeta && end - lineStart > 3) {
                val at = lineStart + 1
                if (copy[at] != '='.code.toByte() && copy[at - 1] != '='.code.toByte()) {
                    copy[at] = if (copy[at] == 'a'.code.toByte()) 'b'.code.toByte() else 'a'.code.toByte()
                    return copy
                }
            }
            lineStart = end + 1
            lines++
        }
        return copy
    }

    private fun send(output: OutputStream, line: String) {
        output.write((line + "\r\n").toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = try {
                input.read()
            } catch (e: java.io.IOException) {
                return null
            }
            if (b < 0) return null
            if (b == '\n'.code) return out.toString(Charsets.ISO_8859_1.name()).trimEnd('\r')
            out.write(b)
        }
    }

    private companion object {
        const val EOF = "\u0000EOF"
    }
}
