package com.seastreams.tv.usenet.nntp

import java.io.IOException
import java.io.InputStream

/** A status line: the 3-digit [code] and the rest of the line. */
data class NntpStatus(val code: Int, val text: String)

/**
 * Line reader over a server's byte stream. Lines are exposed in place ([buffer], [lineStart] until [lineEnd],
 * CR/LF excluded) so the yEnc decoder works on the receive buffer without copying. A line longer than the
 * buffer is a desync ([NntpErrorKind.PROTOCOL]).
 */
class NntpReader(
    private val input: InputStream,
    bufferSize: Int = DEFAULT_BUFFER,
    private val onBytes: (Int) -> Unit = {},
) {
    val buffer = ByteArray(bufferSize)
    private var position = 0
    private var limit = 0

    var lineStart = 0
        private set
    var lineEnd = 0
        private set

    /** Moves to the next line, reading from the server as needed. */
    fun nextLine() {
        val lf = findLineFeed()
        lineStart = position
        var end = lf
        if (end > lineStart && buffer[end - 1] == CR) end--
        lineEnd = end
        position = lf + 1
    }

    val lineLength: Int get() = lineEnd - lineStart

    fun lineString(from: Int = lineStart): String = String(buffer, from, lineEnd - from, Charsets.ISO_8859_1)

    /** The `.` line that ends a multi-line answer. */
    val atTerminator: Boolean get() = lineEnd - lineStart == 1 && buffer[lineStart] == DOT

    fun readStatus(): NntpStatus {
        nextLine()
        if (lineLength < 3) throw NntpException(NntpErrorKind.PROTOCOL, "The news server sent an unreadable answer")
        var code = 0
        for (i in 0 until 3) {
            val digit = buffer[lineStart + i] - ZERO
            if (digit !in 0..9) throw NntpException(NntpErrorKind.PROTOCOL, "The news server sent an unreadable answer")
            code = code * 10 + digit
        }
        return NntpStatus(code, lineString(lineStart + 3).trim())
    }

    /** Reads and drops a multi-line answer up to its terminator. */
    fun skipMultiline() {
        while (true) {
            nextLine()
            if (atTerminator) return
        }
    }

    private fun findLineFeed(): Int {
        var scan = position
        while (true) {
            while (scan < limit) {
                if (buffer[scan] == LF) return scan
                scan++
            }
            if (position > 0) {
                System.arraycopy(buffer, position, buffer, 0, limit - position)
                scan -= position
                limit -= position
                position = 0
            }
            if (limit == buffer.size) throw NntpException(NntpErrorKind.PROTOCOL, "The news server sent an overlong line")
            val count = input.read(buffer, limit, buffer.size - limit)
            if (count < 0) throw NntpException(NntpErrorKind.CONNECTION, "The news server closed the connection")
            limit += count
            onBytes(count)
        }
    }

    companion object {
        const val DEFAULT_BUFFER = 128 * 1024
        const val CR = '\r'.code.toByte()
        const val LF = '\n'.code.toByte()
        const val DOT = '.'.code.toByte()
        private const val ZERO = '0'.code.toByte()

        @Throws(IOException::class)
        fun of(bytes: ByteArray): NntpReader = NntpReader(bytes.inputStream())
    }
}
