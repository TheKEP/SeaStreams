package com.seastreams.tv.usenet.archive

import java.io.EOFException
import java.io.IOException

/**
 * A release that can never stream: compressed or encrypted archives (without a working NZB password), no video, a
 * missing volume, or damage
 * beyond the policy. Lasting: the player doesn't retry it and failover moves on (ARCHITECTURE §10).
 */
open class UsenetUnplayableException(message: String) : IOException(message)

/** The archive set is refused (PRD §12: compressed, encrypted RAR, 7z without / with a wrong NZB password, no video). */
class ArchiveRefusedException(message: String) : UsenetUnplayableException(message)

/** RAR headers that don't parse (truncated, bad CRC, impossible sizes). */
class RarFormatException(message: String) : IOException(message)

/** Random-access bytes of one posted file, a test buffer, or a joined virtual file. Blocking. */
interface RandomAccessData {
    /** Total bytes (may fetch the first article). */
    fun size(): Long

    /** Copies up to [length] bytes at [position]; `-1` at the end. */
    fun read(position: Long, target: ByteArray, offset: Int, length: Int): Int
}

/** One file of a release with the name it was resolved to (subject, else yEnc `name=`). */
interface VolumeData : RandomAccessData {
    val name: String

    /** Σ NZB-stated bytes: known without any download, used to rank and to spot odd-sized middle volumes. */
    val statedBytes: Long
}

/** Reads exactly [length] bytes at [position], fewer only at the end of the data. */
fun RandomAccessData.readUpTo(position: Long, length: Int): ByteArray {
    val out = ByteArray(length)
    var filled = 0
    while (filled < length) {
        val n = read(position + filled, out, filled, length - filled)
        if (n < 0) break
        filled += n
    }
    return if (filled == length) out else out.copyOf(filled)
}

fun RandomAccessData.readExactly(position: Long, length: Int): ByteArray =
    readUpTo(position, length).also { if (it.size < length) throw EOFException("The file ends inside a header") }

/** What a file's first bytes say it is. */
enum class ArchiveKind { RAR4, RAR5, SEVEN_ZIP, ZIP, NONE }

object ArchiveSignatures {
    private val RAR4 = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00)
    private val RAR5 = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00)
    private val SEVEN_ZIP = byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)
    private val ZIP = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    const val PROBE_BYTES = 8

    fun detect(head: ByteArray): ArchiveKind = when {
        head.startsWith(RAR5) -> ArchiveKind.RAR5
        head.startsWith(RAR4) -> ArchiveKind.RAR4
        head.startsWith(SEVEN_ZIP) -> ArchiveKind.SEVEN_ZIP
        head.startsWith(ZIP) -> ArchiveKind.ZIP
        else -> ArchiveKind.NONE
    }

    const val RAR4_SIGNATURE_SIZE = 7
    const val RAR5_SIGNATURE_SIZE = 8

    private fun ByteArray.startsWith(prefix: ByteArray) = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}

/** File extensions that decide what a posted file is (PRD §12 media selection). */
object MediaExtensions {
    val VIDEO = setOf("mkv", "mp4", "m4v", "avi", "ts", "m2ts", "mts", "webm", "mov", "wmv", "mpg", "mpeg", "flv", "vob", "divx")

    /** Never media: verification, info and artwork files. */
    val IGNORED = setOf("par2", "nfo", "sfv", "srr", "srs", "txt", "jpg", "jpeg", "png", "gif", "nzb", "url", "md5", "sha1", "exe", "db")

    fun of(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun isVideo(name: String): Boolean = of(name) in VIDEO
}
