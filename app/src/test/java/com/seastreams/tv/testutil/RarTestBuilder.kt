package com.seastreams.tv.testutil

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/**
 * Builds real stored RAR4 / RAR5 volume sets for archive tests: [payload] (entry [name]) is split over volumes of at
 * most [perVolume] data bytes. Flags make compressed, encrypted-entry or encrypted-header sets for refusal tests.
 */
object RarTestBuilder {

    fun rar4(
        name: String,
        payload: ByteArray,
        perVolume: Int,
        compressed: Boolean = false,
        encrypted: Boolean = false,
        headersEncrypted: Boolean = false,
    ): List<ByteArray> {
        val chunks = chunks(payload, perVolume)
        return chunks.mapIndexed { index, (from, to) ->
            val out = ByteArrayOutputStream()
            out.write(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00))
            var mainFlags = 0
            if (chunks.size > 1) mainFlags = mainFlags or 0x0001
            if (index == 0) mainFlags = mainFlags or 0x0100
            if (headersEncrypted) mainFlags = mainFlags or 0x0080
            out.write(rar4Block(0x73, mainFlags, ByteArray(6)))
            var fileFlags = 0x8000
            if (index > 0) fileFlags = fileFlags or 0x01
            if (index < chunks.size - 1) fileFlags = fileFlags or 0x02
            if (encrypted) fileFlags = fileFlags or 0x04
            val nameBytes = name.toByteArray()
            val fields = ByteArrayOutputStream()
            fields.le32(to - from.toLong())
            fields.le32(payload.size.toLong())
            fields.write(2) // host OS
            fields.le32(0) // file CRC (not checked)
            fields.le32(0) // time
            fields.write(29) // version
            fields.write(if (compressed) 0x33 else 0x30)
            fields.le16(nameBytes.size)
            fields.le32(0x20)
            fields.write(nameBytes)
            out.write(rar4Block(0x74, fileFlags, fields.toByteArray()))
            out.write(payload, from, to - from)
            out.write(byteArrayOf(0, 0, 0x7B, 0, 0, 7, 0))
            out.toByteArray()
        }
    }

    fun rar5(
        name: String,
        payload: ByteArray,
        perVolume: Int,
        compressed: Boolean = false,
        encrypted: Boolean = false,
        headersEncrypted: Boolean = false,
    ): List<ByteArray> {
        val chunks = chunks(payload, perVolume)
        return chunks.mapIndexed { index, (from, to) ->
            val out = ByteArrayOutputStream()
            out.write(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00))
            if (headersEncrypted) out.write(rar5Block(type = 4, flags = 0, body = vint(0) + vint(0)))
            var archiveFlags = if (chunks.size > 1) 0x1L else 0L
            if (index > 0) archiveFlags = archiveFlags or 0x2
            out.write(rar5Block(type = 1, flags = 0, body = vint(archiveFlags) + (if (index > 0) vint(index.toLong()) else ByteArray(0))))
            var flags = 0x0002L
            if (index > 0) flags = flags or 0x08
            if (index < chunks.size - 1) flags = flags or 0x10
            val nameBytes = name.toByteArray()
            val extra = if (encrypted) vint(2) + vint(1) + byteArrayOf(0) else ByteArray(0)
            if (extra.isNotEmpty()) flags = flags or 0x0001
            val body = vint(0) + vint(payload.size.toLong()) + vint(0x20) + vint(if (compressed) 3L shl 7 else 0) + vint(0) +
                vint(nameBytes.size.toLong()) + nameBytes
            out.write(rar5Block(type = 2, flags = flags, body = body, extra = extra, dataSize = (to - from).toLong()))
            out.write(payload, from, to - from)
            out.write(rar5Block(type = 5, flags = 0, body = vint(if (index < chunks.size - 1) 1 else 0)))
            out.toByteArray()
        }
    }

    private fun chunks(payload: ByteArray, perVolume: Int): List<Pair<Int, Int>> =
        (payload.indices step perVolume).map { it to minOf(it + perVolume, payload.size) }.ifEmpty { listOf(0 to 0) }

    private fun rar4Block(type: Int, flags: Int, fields: ByteArray): ByteArray {
        val size = 7 + fields.size
        val header = ByteArrayOutputStream()
        header.write(type)
        header.le16(flags)
        header.le16(size)
        header.write(fields)
        val bytes = header.toByteArray()
        val crc = CRC32().apply { update(bytes) }.value and 0xFFFF
        return ByteArrayOutputStream().apply {
            le16(crc.toInt())
            write(bytes)
        }.toByteArray()
    }

    private fun rar5Block(type: Long, flags: Long, body: ByteArray, extra: ByteArray = ByteArray(0), dataSize: Long? = null): ByteArray {
        val inner = vint(type) + vint(flags) + (if (extra.isNotEmpty()) vint(extra.size.toLong()) else ByteArray(0)) +
            (dataSize?.let(::vint) ?: ByteArray(0)) + body + extra
        val sized = vint(inner.size.toLong()) + inner
        val crc = CRC32().apply { update(sized) }.value
        return ByteArrayOutputStream().apply {
            le32(crc)
            write(sized)
        }.toByteArray()
    }

    fun vint(value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var v = value
        do {
            val low = (v and 0x7F).toInt()
            v = v ushr 7
            out.write(if (v != 0L) low or 0x80 else low)
        } while (v != 0L)
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.le16(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
    }

    private fun ByteArrayOutputStream.le32(value: Long) {
        le16((value and 0xFFFF).toInt())
        le16(((value ushr 16) and 0xFFFF).toInt())
    }
}
