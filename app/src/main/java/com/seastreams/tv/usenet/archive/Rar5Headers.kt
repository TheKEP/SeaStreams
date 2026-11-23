package com.seastreams.tv.usenet.archive

import java.util.zip.CRC32

/**
 * RAR 5.0 block headers (ARCHITECTURE §10 Archives): `CRC32, size vint, type vint, flags vint, [extra size],
 * [data size], …`. Main (`1`), file (`2`), service (`3`), encryption (`4`, = encrypted headers) and end (`5`)
 * blocks are walked by their sizes; only header bytes are read. File encryption is an extra-area record (type 1).
 */
object Rar5Headers {
    private const val MAIN = 1L
    private const val FILE = 2L
    private const val SERVICE = 3L
    private const val ENCRYPTION = 4L
    private const val END = 5L
    private const val HAS_EXTRA = 0x0001L
    private const val HAS_DATA = 0x0002L
    private const val SPLIT_BEFORE = 0x0008L
    private const val SPLIT_AFTER = 0x0010L
    private const val ARCHIVE_VOLUME = 0x0001L
    private const val ARCHIVE_NUMBER = 0x0002L
    private const val FILE_DIRECTORY = 0x0001L
    private const val FILE_MTIME = 0x0002L
    private const val FILE_CRC = 0x0004L
    private const val FILE_UNKNOWN_SIZE = 0x0008L
    private const val EXTRA_ENCRYPTION = 1L
    private const val MAX_VINT = 10
    private const val MAX_HEADER = 2 * 1024 * 1024
    private const val MAX_BLOCKS = 4_096

    fun parse(volume: RandomAccessData, maxEntries: Int): RarVolumeInfo {
        val size = volume.size()
        var at = ArchiveSignatures.RAR5_SIGNATURE_SIZE.toLong()
        var isVolume = false
        var number: Int? = null
        val entries = ArrayList<RarEntry>()
        var blocks = 0
        while (blocks++ < MAX_BLOCKS && at + 5 <= size && entries.size < maxEntries) {
            val probe = volume.readUpTo(at, 4 + MAX_VINT)
            val sizeReader = Cursor(probe, 4)
            val headerSize = sizeReader.vint()
            val sizeLength = sizeReader.at - 4
            if (headerSize <= 0 || headerSize > MAX_HEADER) throw RarFormatException("A RAR5 header has an impossible size")
            val total = 4 + sizeLength + headerSize.toInt()
            val block = volume.readExactly(at, total)
            val crc = CRC32().apply { update(block, 4, total - 4) }.value
            if (crc != RarHeaders.le32(block, 0)) throw RarFormatException("A RAR5 header failed its checksum")
            val c = Cursor(block, 4 + sizeLength)
            val type = c.vint()
            val flags = c.vint()
            val extraSize = if (flags and HAS_EXTRA != 0L) c.vint() else 0L
            val dataSize = if (flags and HAS_DATA != 0L) c.vint() else 0L
            when (type) {
                MAIN -> {
                    val archiveFlags = c.vint()
                    isVolume = archiveFlags and ARCHIVE_VOLUME != 0L
                    number = if (archiveFlags and ARCHIVE_NUMBER != 0L) c.vint().toInt() else 0
                }
                ENCRYPTION -> return RarVolumeInfo(5, isVolume, number, true, entries)
                FILE, SERVICE -> {
                    val fileFlags = c.vint()
                    val unpacked = c.vint()
                    c.vint() // attributes
                    if (fileFlags and FILE_MTIME != 0L) c.skip(4)
                    if (fileFlags and FILE_CRC != 0L) c.skip(4)
                    val compression = c.vint()
                    c.vint() // host OS
                    val nameLength = c.vint().toInt()
                    val name = c.string(nameLength)
                    val extraStart = total - extraSize.toInt()
                    if (type == FILE) {
                        entries += RarEntry(
                            name = name.replace('\\', '/'),
                            packedSize = dataSize,
                            unpackedSize = if (fileFlags and FILE_UNKNOWN_SIZE != 0L) -1 else unpacked,
                            dataOffset = at + total,
                            stored = (compression shr 7) and 0x7 == 0L,
                            encrypted = hasEncryptionRecord(block, extraStart, total),
                            directory = fileFlags and FILE_DIRECTORY != 0L,
                            splitBefore = flags and SPLIT_BEFORE != 0L,
                            splitAfter = flags and SPLIT_AFTER != 0L,
                        )
                    }
                }
                END -> return RarVolumeInfo(5, isVolume, number, false, entries)
            }
            at += total + dataSize
        }
        return RarVolumeInfo(5, isVolume, number, false, entries)
    }

    private fun hasEncryptionRecord(block: ByteArray, from: Int, to: Int): Boolean {
        if (from !in 0 until to) return false
        val c = Cursor(block, from)
        while (c.at < to) {
            val recordSize = c.vint()
            val start = c.at
            if (recordSize <= 0 || start + recordSize > to) return false
            if (c.vint() == EXTRA_ENCRYPTION) return true
            c.at = (start + recordSize).toInt()
        }
        return false
    }

    /** Reads RAR5 variable-length integers (7 bits per byte, high bit = more) and fields from one header. */
    private class Cursor(val bytes: ByteArray, var at: Int) {
        fun vint(): Long {
            var value = 0L
            var shift = 0
            while (shift < 64) {
                if (at >= bytes.size) throw RarFormatException("A RAR5 header ends inside a number")
                val b = bytes[at++].toInt() and 0xFF
                value = value or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return value
                shift += 7
            }
            throw RarFormatException("A RAR5 number is too long")
        }

        fun skip(n: Int) {
            at += n
            if (at > bytes.size) throw RarFormatException("A RAR5 header is truncated")
        }

        fun string(n: Int): String {
            if (n < 0 || at + n > bytes.size) throw RarFormatException("A RAR5 file name overruns its header")
            return String(bytes, at, n, Charsets.UTF_8).also { at += n }
        }
    }
}
