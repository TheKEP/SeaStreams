package com.seastreams.tv.usenet.yenc

import com.seastreams.tv.usenet.nntp.NntpReader
import java.io.IOException
import java.util.zip.CRC32

/**
 * The article's yEnc data is unusable ([code]: `crc_mismatch`, `size_mismatch`, `wrong_part`, `overflow`,
 * `bad_header`, `truncated`, `no_yenc`). The body was read to its end, so the connection stays in step and
 * only this article fails; the next provider may hold a good copy. [terminal] = no provider can fix it
 * (the post isn't yEnc at all).
 */
class YencException(val code: String, message: String, val terminal: Boolean = false) : IOException(message)

/**
 * Single-pass yEnc decoder (ARCHITECTURE §10) that works on the [NntpReader]'s receive buffer and writes into a
 * [SegmentBufferPool] buffer. Reads everything up to the `.` terminator, undoing dot-stuffing, then checks:
 * - `wrong_part`: `=ybegin part=` must match the NZB segment number (a reused Message-ID can serve another post);
 * - `size_mismatch`: the decoded length must equal the `=ypart` span, and `=yend size=` when no CRC is checked;
 * - `crc_mismatch` (only with [verifyCrc]): `pcrc32`, or `crc32` only on a single-part post, since a bare
 *   `crc32` on a multi-part post covers the whole file. Compared numerically (posters drop leading zeros).
 */
class YencDecoder(private val pool: SegmentBufferPool) {

    fun decode(reader: NntpReader, expectedPart: Int?, verifyCrc: Boolean): DecodedSegment {
        val buffer = reader.buffer
        var data: ByteArray? = null
        var room = 0
        var filled = 0
        var header: String? = null
        var partLine: String? = null
        var trailer: String? = null
        var failure: YencException? = null
        var fileSize = -1L
        var spanBegin = -1L
        var spanEnd = -1L
        try {
            while (true) {
                reader.nextLine()
                if (reader.atTerminator) break
                var start = reader.lineStart
                val stop = reader.lineEnd
                if (stop > start && buffer[start] == NntpReader.DOT) start++
                if (stop - start >= 2 && buffer[start] == EQUALS && buffer[start + 1] == LOWER_Y) {
                    val line = reader.lineString(start)
                    when {
                        line.startsWith("=ybegin ") && header == null -> {
                            header = line
                            fileSize = field(line, "size")?.toLongOrNull() ?: -1L
                        }
                        line.startsWith("=ypart ") && partLine == null -> {
                            partLine = line
                            spanBegin = field(line, "begin")?.toLongOrNull() ?: -1L
                            spanEnd = field(line, "end")?.toLongOrNull() ?: -1L
                        }
                        line.startsWith("=yend") -> trailer = line
                    }
                    continue
                }
                if (header == null || trailer != null || failure != null) continue
                if (data == null) {
                    val expected = when {
                        partLine != null -> if (spanBegin >= 1 && spanEnd >= spanBegin) spanEnd - spanBegin + 1 else -1L
                        else -> fileSize
                    }
                    if (expected <= 0 || expected > MAX_PART) {
                        failure = YencException("bad_header", "The article's yEnc header is unusable")
                        continue
                    }
                    data = pool.acquire(expected.toInt())
                    room = expected.toInt()
                }
                var index = start
                while (index < stop) {
                    var value = buffer[index++].toInt()
                    if (value == ESCAPE) {
                        if (index >= stop) break
                        value = buffer[index++].toInt() - 64
                    }
                    if (filled >= room) {
                        failure = YencException("overflow", "The article holds more data than it states")
                        break
                    }
                    data[filled++] = (value - 42).toByte()
                }
            }
        } catch (e: Throwable) {
            data?.let(pool::recycle) // dropped or stalled mid-article: the buffer goes back to the pool
            throw e
        }
        try {
            return check(data, filled, header, partLine, trailer, failure, fileSize, spanBegin, spanEnd, expectedPart, verifyCrc)
        } catch (e: YencException) {
            data?.let(pool::recycle)
            throw e
        }
    }

    private fun check(
        data: ByteArray?,
        filled: Int,
        header: String?,
        partLine: String?,
        trailer: String?,
        failure: YencException?,
        fileSize: Long,
        spanBegin: Long,
        spanEnd: Long,
        expectedPart: Int?,
        verifyCrc: Boolean,
    ): DecodedSegment {
        if (failure != null) throw failure
        if (header == null) throw YencException("no_yenc", "The article isn't yEnc encoded", terminal = true)
        if (data == null) throw YencException("bad_header", "The article holds no yEnc data")
        if (trailer == null) throw YencException("truncated", "The article ended before its yEnc trailer")
        val part = field(header, "part")?.toIntOrNull()
        val total = field(header, "total")?.toIntOrNull()
        if (part != null && expectedPart != null && part != expectedPart) {
            throw YencException("wrong_part", "The article is part $part, not part $expectedPart")
        }
        if (partLine != null && spanEnd - spanBegin + 1 != filled.toLong()) {
            throw YencException("size_mismatch", "The article's part spans ${spanEnd - spanBegin + 1} bytes but decoded $filled")
        }
        val multipart = partLine != null || (total != null && total > 1)
        val crcHex = if (verifyCrc) field(trailer, "pcrc32") ?: if (multipart) null else field(trailer, "crc32") else null
        val expectedCrc = crcHex?.toLongOrNull(16)
        if (expectedCrc != null) {
            val crc = CRC32().apply { update(data, 0, filled) }.value
            if (crc != expectedCrc) throw YencException("crc_mismatch", "The article failed its checksum")
        } else {
            val size = field(trailer, "size")?.toLongOrNull()
            if (size != null && size != filled.toLong()) {
                throw YencException("size_mismatch", "The article's trailer states $size bytes but decoded $filled")
            }
        }
        val begin = if (partLine != null) spanBegin - 1 else 0L
        val size = if (fileSize > 0) fileSize else begin + filled
        return DecodedSegment(data, filled, begin, size, nameOf(header), part, total, pool)
    }

    companion object {
        private const val EQUALS = '='.code.toByte()
        private const val LOWER_Y = 'y'.code.toByte()
        private const val ESCAPE = '='.code

        /** Articles are well under a megabyte; anything far larger is not a normal post. */
        const val MAX_PART = 16L * 1024 * 1024

        /** `key=value` from a `=y…` line (`name=` is excluded: it runs to the end of the line). */
        internal fun field(line: String, key: String): String? {
            val nameAt = line.indexOf(" name=").let { if (it < 0) line.length else it }
            val at = line.indexOf(" $key=")
            if (at < 0 || at >= nameAt) return null
            val from = at + key.length + 2
            val to = line.indexOf(' ', from).let { if (it < 0 || it > nameAt) nameAt else it }
            return line.substring(from, to)
        }

        /** `name=` is always last on `=ybegin` and may contain spaces. */
        internal fun nameOf(header: String): String = header.substringAfter(" name=", "").trim()
    }
}
