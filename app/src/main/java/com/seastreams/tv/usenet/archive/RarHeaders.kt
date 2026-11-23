package com.seastreams.tv.usenet.archive

import java.util.zip.CRC32

/** One file entry of one RAR volume: its stored bytes are `[dataOffset, dataOffset + packedSize)` of the volume. */
data class RarEntry(
    val name: String,
    val packedSize: Long,
    /** The whole file's size (same in every volume's header); `-1` if the header doesn't say. */
    val unpackedSize: Long,
    val dataOffset: Long,
    /** Stored without compression (RAR4 method `0x30`, RAR5 method 0): the only kind that streams. */
    val stored: Boolean,
    val encrypted: Boolean,
    val directory: Boolean,
    /** Continues from the previous volume / into the next one. */
    val splitBefore: Boolean,
    val splitAfter: Boolean,
)

/** What one volume's headers say. */
data class RarVolumeInfo(
    val version: Int,
    val isVolume: Boolean,
    /** RAR5 volume number (0 = first); RAR4: 0 when the first-volume flag is set, else `null`. */
    val volumeNumber: Int?,
    /** Headers are encrypted (`-hp`): nothing can be read without the password. */
    val headersEncrypted: Boolean,
    val entries: List<RarEntry>,
)

/** RAR headers of either version, picked by signature. */
object RarVolumeParser {
    fun parse(volume: RandomAccessData, maxEntries: Int = MAX_ENTRIES): RarVolumeInfo {
        val head = volume.readUpTo(0, ArchiveSignatures.PROBE_BYTES)
        return when (ArchiveSignatures.detect(head)) {
            ArchiveKind.RAR4 -> RarHeaders.parse(volume, maxEntries)
            ArchiveKind.RAR5 -> Rar5Headers.parse(volume, maxEntries)
            else -> throw RarFormatException("Not a RAR volume")
        }
    }

    const val MAX_ENTRIES = 64
}

/**
 * RAR 1.5–4.x block headers (ARCHITECTURE §10 Archives): marker, main header (`0x73`), file headers (`0x74`)
 * and service blocks are walked with each block's size, so only header bytes are read (a few hundred bytes per
 * volume); entry data is never touched. Header CRC16s are checked.
 */
object RarHeaders {
    private const val MAIN = 0x73
    private const val FILE = 0x74
    private const val SERVICE = 0x7A
    private const val END = 0x7B
    private const val LONG_BLOCK = 0x8000
    private const val MAIN_VOLUME = 0x0001
    private const val MAIN_PASSWORD = 0x0080
    private const val MAIN_FIRST_VOLUME = 0x0100
    private const val FILE_SPLIT_BEFORE = 0x01
    private const val FILE_SPLIT_AFTER = 0x02
    private const val FILE_PASSWORD = 0x04
    private const val FILE_DIRECTORY = 0xE0
    private const val FILE_LARGE = 0x100
    private const val FILE_UNICODE = 0x200
    private const val METHOD_STORE = 0x30
    private const val BASE_HEADER = 7
    private const val FILE_FIXED = 25
    private const val MAX_BLOCKS = 4_096

    fun parse(volume: RandomAccessData, maxEntries: Int): RarVolumeInfo {
        val size = volume.size()
        var at = ArchiveSignatures.RAR4_SIGNATURE_SIZE.toLong()
        var isVolume = false
        var first = false
        var encrypted = false
        val entries = ArrayList<RarEntry>()
        var blocks = 0
        while (blocks++ < MAX_BLOCKS && at + BASE_HEADER <= size && entries.size < maxEntries) {
            val base = volume.readExactly(at, BASE_HEADER)
            val type = base[2].toInt() and 0xFF
            val flags = le16(base, 3)
            val headSize = le16(base, 5)
            if (headSize < BASE_HEADER) throw RarFormatException("A RAR header is too short")
            val header = volume.readExactly(at, headSize)
            if (type == FILE || type == MAIN) checkCrc(header)
            var dataSize = if (flags and LONG_BLOCK != 0 && headSize >= 11) le32(header, 7) else 0L
            when (type) {
                MAIN -> {
                    isVolume = flags and MAIN_VOLUME != 0
                    first = flags and MAIN_FIRST_VOLUME != 0
                    encrypted = flags and MAIN_PASSWORD != 0
                    if (encrypted) return RarVolumeInfo(4, isVolume, if (first) 0 else null, true, entries)
                }
                FILE, SERVICE -> {
                    if (headSize < FILE_FIXED + 7) throw RarFormatException("A RAR file header is too short")
                    var packed = le32(header, 7)
                    var unpacked = le32(header, 11)
                    val method = header[25].toInt() and 0xFF
                    val nameSize = le16(header, 26)
                    var nameAt = 32
                    if (flags and FILE_LARGE != 0) {
                        packed = packed or (le32(header, 32) shl 32)
                        unpacked = unpacked or (le32(header, 36) shl 32)
                        nameAt = 40
                    }
                    if (nameAt + nameSize > headSize) throw RarFormatException("A RAR file name overruns its header")
                    dataSize = packed
                    if (type == FILE) {
                        entries += RarEntry(
                            name = name(header, nameAt, nameSize, flags and FILE_UNICODE != 0),
                            packedSize = packed,
                            unpackedSize = unpacked,
                            dataOffset = at + headSize,
                            stored = method == METHOD_STORE,
                            encrypted = flags and FILE_PASSWORD != 0,
                            directory = flags and FILE_DIRECTORY == FILE_DIRECTORY,
                            splitBefore = flags and FILE_SPLIT_BEFORE != 0,
                            splitAfter = flags and FILE_SPLIT_AFTER != 0,
                        )
                    }
                }
                END -> return RarVolumeInfo(4, isVolume, if (first) 0 else null, encrypted, entries)
            }
            at += headSize + dataSize
        }
        return RarVolumeInfo(4, isVolume, if (first) 0 else null, encrypted, entries)
    }

    /** Unicode names store `ascii\0encoded`; the ASCII part is what SeaStreams matches on. */
    private fun name(header: ByteArray, at: Int, size: Int, unicode: Boolean): String {
        var end = at + size
        if (unicode) (at until end).firstOrNull { header[it].toInt() == 0 }?.let { end = it }
        return String(header, at, end - at, Charsets.UTF_8).replace('\\', '/')
    }

    private fun checkCrc(header: ByteArray) {
        val crc = CRC32().apply { update(header, 2, header.size - 2) }.value and 0xFFFF
        if (crc.toInt() != le16(header, 0)) throw RarFormatException("A RAR header failed its checksum")
    }

    internal fun le16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    internal fun le32(b: ByteArray, at: Int): Long =
        (le16(b, at).toLong()) or (le16(b, at + 2).toLong() shl 16)
}
