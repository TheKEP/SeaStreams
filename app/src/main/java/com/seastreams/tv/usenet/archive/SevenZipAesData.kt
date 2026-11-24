package com.seastreams.tv.usenet.archive

import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The `7zAES` coder's settings: key derivation rounds (`2^cyclesPower`), salt and the folder's first IV. */
internal class SevenZipAesParams(val cyclesPower: Int, val salt: ByteArray, val iv: ByteArray) {
    companion object {
        /** 7-Zip's default is 19; anything above 20 would take minutes on a TV and is refused. */
        const val MAX_CYCLES_POWER = 20
        private const val RAW_KEY = 0x3F

        /** Parses the coder properties (7-Zip `7zAes.cpp` layout); refuses impossible or too costly values. */
        fun parse(properties: ByteArray): SevenZipAesParams {
            if (properties.size < 2) throw ArchiveRefusedException("The 7z encryption settings are damaged")
            val first = properties[0].toInt() and 0xFF
            val second = properties[1].toInt() and 0xFF
            val power = first and 0x3F
            val saltSize = ((first shr 7) and 1) + (second shr 4)
            val ivSize = ((first shr 6) and 1) + (second and 0x0F)
            if (2 + saltSize + ivSize > properties.size) throw ArchiveRefusedException("The 7z encryption settings are damaged")
            if (power != RAW_KEY && power > MAX_CYCLES_POWER) {
                throw ArchiveRefusedException("The 7z password needs too much processing to stream on a TV")
            }
            val salt = properties.copyOfRange(2, 2 + saltSize)
            val iv = ByteArray(BLOCK)
            properties.copyInto(iv, 0, 2 + saltSize, 2 + saltSize + ivSize)
            return SevenZipAesParams(power, salt, iv)
        }

        /**
         * The AES-256 key: SHA-256 over `2^power` rounds of salt ‖ UTF-16LE password ‖ 8-byte LE counter. The rounds
         * are laid end to end in a buffer and hashed a few thousand at a time (the same bytes, so the same key):
         * one `update` per round costs seconds on a TV. Power `0x3F` means salt ‖ password used as the key directly.
         */
        fun deriveKey(params: SevenZipAesParams, password: String): ByteArray {
            val secret = password.toByteArray(Charsets.UTF_16LE)
            try {
                if (params.cyclesPower == RAW_KEY) {
                    return ByteArray(KEY_BYTES).also { key ->
                        params.salt.copyInto(key, 0, 0, minOf(params.salt.size, KEY_BYTES))
                        val room = KEY_BYTES - minOf(params.salt.size, KEY_BYTES)
                        secret.copyInto(key, KEY_BYTES - room, 0, minOf(secret.size, room))
                    }
                }
                val digest = MessageDigest.getInstance("SHA-256")
                val round = params.salt.size + secret.size + COUNTER_BYTES
                val perChunk = maxOf(1, CHUNK_BYTES / round)
                val chunk = ByteArray(perChunk * round)
                for (i in 0 until perChunk) {
                    params.salt.copyInto(chunk, i * round)
                    secret.copyInto(chunk, i * round + params.salt.size)
                }
                var counter = 0L
                var left = 1L shl params.cyclesPower
                try {
                    while (left > 0) {
                        val rounds = minOf(left, perChunk.toLong()).toInt()
                        for (i in 0 until rounds) {
                            var value = counter++
                            val at = i * round + params.salt.size + secret.size
                            for (b in 0 until COUNTER_BYTES) {
                                chunk[at + b] = value.toByte()
                                value = value ushr 8
                            }
                        }
                        digest.update(chunk, 0, rounds * round)
                        left -= rounds
                    }
                } finally {
                    chunk.fill(0)
                }
                return digest.digest()
            } finally {
                secret.fill(0)
            }
        }

        const val BLOCK = 16
        private const val KEY_BYTES = 32
        private const val COUNTER_BYTES = 8
        private const val CHUNK_BYTES = 256 * 1024
    }
}

/**
 * A stored entry of an AES + Copy 7z folder, decrypted on demand (ARCHITECTURE §10). [packed] is the folder's
 * encrypted pack stream (a [JoinedData] slice, so the read-ahead follows the volumes); the entry starts [skip] bytes
 * into the decrypted folder. AES-CBC decrypts any block given the ciphertext block before it, so a seek decrypts
 * only one [window] (a multiple of 16) starting at the block holding the position, never the bytes before it. One
 * [Cipher] is reused (creating one per read stalls a TV); the player's many tiny index reads are served from the
 * last window. The key and window are zeroed on [close].
 */
internal class SevenZipAesData(
    val packed: JoinedData,
    private val skip: Long,
    private val size: Long,
    key: ByteArray,
    private val firstIv: ByteArray,
    private val window: Int = DEFAULT_WINDOW,
) : RandomAccessData, Closeable {
    private val key = key.copyOf()
    private val secret = SecretKeySpec(this.key, "AES")
    private val cipher = Cipher.getInstance("AES/CBC/NoPadding")
    private val packedSize = packed.size()
    private var windowStart = -1L
    private var plain = ByteArray(0)
    private var closed = false

    init {
        require(window > 0 && window % SevenZipAesParams.BLOCK == 0) { "The window must be whole AES blocks" }
        require(skip >= 0 && size >= 0 && skip + size <= packedSize) { "The entry lies outside its folder" }
    }

    override fun size(): Long = size

    @Synchronized
    override fun read(position: Long, target: ByteArray, offset: Int, length: Int): Int {
        if (closed) throw IOException("The 7z stream is closed")
        if (position >= size) return -1
        if (length == 0) return 0
        val absolute = skip + position
        if (windowStart < 0 || absolute < windowStart || absolute >= windowStart + plain.size) {
            fill(absolute / SevenZipAesParams.BLOCK * SevenZipAesParams.BLOCK)
        }
        val at = (absolute - windowStart).toInt()
        val count = minOf(length.toLong(), size - position, (plain.size - at).toLong()).toInt()
        System.arraycopy(plain, at, target, offset, count)
        return count
    }

    /** Decrypts one window from [block]; never past the entry's end rounded up to a whole block. */
    private fun fill(block: Long) {
        val entryEnd = minOf(packedSize, (skip + size + SevenZipAesParams.BLOCK - 1) / SevenZipAesParams.BLOCK * SevenZipAesParams.BLOCK)
        val length = minOf(window.toLong(), entryEnd - block).toInt()
        if (length <= 0 || length % SevenZipAesParams.BLOCK != 0) throw EOFException("The encrypted 7z data ends early")
        val iv = if (block == 0L) firstIv else packed.readExactly(block - SevenZipAesParams.BLOCK, SevenZipAesParams.BLOCK)
        val encrypted = packed.readExactly(block, length)
        cipher.init(Cipher.DECRYPT_MODE, secret, IvParameterSpec(iv))
        plain.fill(0)
        plain = cipher.doFinal(encrypted)
        windowStart = block
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        key.fill(0)
        plain.fill(0)
    }

    private companion object {
        const val DEFAULT_WINDOW = 256 * 1024
    }
}
