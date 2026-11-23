package com.seastreams.tv.core.security

import com.seastreams.tv.testutil.FakeSecretCipher
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The AES-GCM sealing [KeystoreSecretCipher] uses, run with a software AES-256 key (the Android Keystore isn't on the
 * JVM): every Debrid key / token and Usenet secret round-trips, and anything else decrypts to `null`.
 */
class AesGcmSecretCipherTest {

    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private val key = newKey()
    private val cipher = AesGcmSecretCipher { key }

    @Test
    fun `secrets round-trip and the stored form never contains the plain text`() {
        listOf("rd-token-ABCDEF0123456789", "pässwörd ✓", "x".repeat(4096), "").forEach { secret ->
            val stored = cipher.encrypt(secret)
            assertTrue(stored.startsWith("v1:"))
            if (secret.isNotEmpty()) assertFalse(stored.contains(secret))
            assertEquals(secret, cipher.decrypt(stored))
        }
    }

    @Test
    fun `each encryption uses a fresh IV`() {
        assertNotEquals(cipher.encrypt("same"), cipher.encrypt("same"))
    }

    @Test
    fun `tampered, foreign, unprefixed or truncated values read as undecryptable`() {
        val stored = cipher.encrypt("token")
        val bytes = Base64.getDecoder().decode(stored.removePrefix("v1:"))
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        assertNull(cipher.decrypt("v1:" + Base64.getEncoder().encodeToString(bytes)))
        // A different key, e.g. after a restore to another device: *Sign-in needed*.
        assertNull(AesGcmSecretCipher { newKey() }.decrypt(stored))
        assertNull(cipher.decrypt(stored.removePrefix("v1:")))
        assertNull(cipher.decrypt("v1:AAAA"))
        assertNull(cipher.decrypt("v1:not base64!"))
    }

    @Test
    fun `the test fake round-trips and can simulate a lost key`() {
        val fake = FakeSecretCipher()
        assertEquals("secret", fake.decrypt(fake.encrypt("secret")))
        fake.broken = true
        assertNull(fake.decrypt(fake.encrypt("secret")))
    }
}
