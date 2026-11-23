package com.seastreams.tv.core.util

import java.security.MessageDigest

private val HEX = "0123456789abcdef".toCharArray()

/** Lower-case hex of these bytes. */
fun ByteArray.toHex(): String {
    val out = CharArray(size * 2)
    for (i in indices) {
        val value = this[i].toInt() and 0xFF
        out[i * 2] = HEX[value ushr 4]
        out[i * 2 + 1] = HEX[value and 0x0F]
    }
    return String(out)
}

/** SHA-1 of [text] (UTF-8) as lower-case hex: cache keys and fingerprints that must not reveal their input. */
fun sha1Hex(text: String): String = MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8)).toHex()

/** SHA-256 of [text] (UTF-8) as lower-case hex: fingerprints of values that may carry secrets (signed stream URLs). */
fun sha256Hex(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).toHex()
