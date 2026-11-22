package com.seastreams.tv.testutil

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/**
 * A reference yEnc encoder for tests: escapes NUL/LF/CR/`=`, tab/space at line edges, and leaves a leading `.`
 * unescaped so NNTP dot-stuffing (`..`) is exercised. Output is the article body as sent after `222`, CRLF
 * lines, without the `.` terminator.
 */
object YencTestEncoder {

    fun crc(data: ByteArray, from: Int = 0, to: Int = data.size): Long = CRC32().apply { update(data, from, to - from) }.value

    /**
     * Encodes `data[from until to]` as one part. [part] = `null` writes a single-part post (no `=ypart`, `crc32`).
     * [trailerCrc] overrides the written checksum; [dropTrailer] omits `=yend`.
     */
    fun encode(
        data: ByteArray,
        from: Int = 0,
        to: Int = data.size,
        fileSize: Long = data.size.toLong(),
        name: String = "file.bin",
        part: Int? = null,
        total: Int? = null,
        lineLength: Int = 128,
        trailerCrc: Long? = null,
        writeCrc: Boolean = true,
        dropTrailer: Boolean = false,
        statedPartSize: Int? = null,
        wholeFileCrc: Long? = null,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        fun line(text: String) = out.write((text + "\r\n").toByteArray(Charsets.ISO_8859_1))
        val partField = if (part != null) " part=$part" + (total?.let { " total=$it" } ?: "") else ""
        line("=ybegin$partField line=$lineLength size=$fileSize name=$name")
        if (part != null) line("=ypart begin=${from + 1} end=${from + (statedPartSize ?: (to - from))}")
        val current = ByteArrayOutputStream()
        var column = 0
        fun flushLine() {
            val bytes = current.toByteArray()
            if (bytes.isNotEmpty() && bytes[0] == '.'.code.toByte()) out.write('.'.code)
            out.write(bytes)
            out.write("\r\n".toByteArray())
            current.reset()
            column = 0
        }
        for (i in from until to) {
            val encoded = (data[i].toInt() + 42) and 0xFF
            val atStart = column == 0
            val atEnd = column >= lineLength - 1 || i == to - 1
            val critical = encoded == 0 || encoded == 0x0A || encoded == 0x0D || encoded == '='.code ||
                ((encoded == 0x09 || encoded == 0x20) && (atStart || atEnd))
            if (critical) {
                current.write('='.code)
                current.write((encoded + 64) and 0xFF)
                column += 2
            } else {
                current.write(encoded)
                column++
            }
            if (column >= lineLength) flushLine()
        }
        if (current.size() > 0) flushLine()
        if (!dropTrailer) {
            val checksum = trailerCrc ?: crc(data, from, to)
            val crcField = when {
                !writeCrc -> ""
                part != null -> " pcrc32=%08x".format(checksum) + (wholeFileCrc?.let { " crc32=%08x".format(it) } ?: "")
                else -> " crc32=%08x".format(checksum)
            }
            line("=yend size=${to - from}" + (part?.let { " part=$it" } ?: "") + crcField)
        }
        return out.toByteArray()
    }
}
