package com.seastreams.tv.usenet.archive

import java.security.MessageDigest
import java.security.MessageDigestSpi
import java.security.Provider
import java.security.Security

/**
 * commons-compress derives an encrypted 7z header's key with three tiny `MessageDigest.update` calls per round,
 * 2^19 rounds: on a TV each call crosses into native code, so that alone takes seconds. While at least one 7z
 * directory is being read ([during]), SHA-256 is served by [Buffered], which gathers small updates and hands them
 * to the platform SHA-256 in 64 KB blocks. The same bytes are hashed, so every digest (any caller's) is unchanged.
 * Reference-counted, so parallel opens never wait on each other; if the provider can't be installed the block just
 * runs slower.
 */
internal object BufferedSha256 {
    private const val NAME = "SeaStreamsBufferedSha256"
    private val lock = Any()
    private var users = 0
    private var installed = false

    @Volatile
    internal var platform: Provider? = null

    fun <T> during(block: () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }

    private fun acquire() = synchronized(lock) {
        users++
        if (users == 1) {
            installed = try {
                if (platform == null) platform = Security.getProviders("MessageDigest.SHA-256")?.firstOrNull { it.name != NAME }
                platform != null && Security.getProvider(NAME) == null && Security.insertProviderAt(Holder(), 1) > 0
            } catch (e: SecurityException) {
                false
            }
        }
    }

    private fun release() = synchronized(lock) {
        users--
        if (users == 0 && installed) {
            installed = false
            try {
                Security.removeProvider(NAME)
            } catch (e: SecurityException) {
                // Left installed: still byte-identical SHA-256.
            }
        }
    }

    // The (String, double, String) constructor: the String-version one is missing before Android API 34.
    @Suppress("DEPRECATION")
    private class Holder : Provider(NAME, 1.0, "Buffered SHA-256 for 7z key derivation") {
        init {
            put("MessageDigest.SHA-256", Buffered::class.java.name)
        }
    }

    /** Instantiated by the security framework by name: must stay public with a no-argument constructor. */
    class Buffered : MessageDigestSpi() {
        private val digest = platform?.let { MessageDigest.getInstance("SHA-256", it) } ?: MessageDigest.getInstance("SHA-256")
        private val buffer = ByteArray(BUFFER_BYTES)
        private var filled = 0

        override fun engineUpdate(input: Byte) {
            if (filled == buffer.size) flush()
            buffer[filled++] = input
        }

        override fun engineUpdate(input: ByteArray, offset: Int, len: Int) {
            if (len >= buffer.size) {
                flush()
                digest.update(input, offset, len)
                return
            }
            if (filled + len > buffer.size) flush()
            System.arraycopy(input, offset, buffer, filled, len)
            filled += len
        }

        override fun engineDigest(): ByteArray {
            flush()
            return digest.digest()
        }

        override fun engineReset() {
            filled = 0
            digest.reset()
        }

        override fun engineGetDigestLength(): Int = DIGEST_BYTES

        private fun flush() {
            if (filled > 0) digest.update(buffer, 0, filled)
            filled = 0
        }
    }

    private const val BUFFER_BYTES = 64 * 1024
    private const val DIGEST_BYTES = 32
}
