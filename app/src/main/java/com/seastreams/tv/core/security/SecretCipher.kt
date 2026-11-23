package com.seastreams.tv.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encrypts Usenet provider passwords, indexer API keys and Debrid keys/tokens before they reach Room (PRD §12, §14).
 * Output is `v1:` + Base64(IV ‖ ciphertext). [decrypt] returns `null` for anything it can't open (e.g. the Keystore
 * key is gone after a restore to another device), which the UI shows as *Password needed* / *Sign-in needed*.
 * Calls may touch the Keystore, so run them off the main thread.
 */
interface SecretCipher {
    fun encrypt(plain: String): String

    fun decrypt(stored: String): String?
}

/** AES-GCM sealing with the key [key] supplies: a fresh 96-bit IV per call and a 128-bit tag, so tampering fails. */
open class AesGcmSecretCipher(private val key: () -> SecretKey) : SecretCipher {

    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.getEncoder().encodeToString(sealed)
    }

    override fun decrypt(stored: String): String? {
        if (!stored.startsWith(PREFIX)) return null
        return try {
            val sealed = Base64.getDecoder().decode(stored.removePrefix(PREFIX))
            if (sealed.size <= IV_BYTES) return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
            String(cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES), Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PREFIX = "v1:"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

/** Android Keystore AES-256-GCM, key alias `seastreams_sources`; the key never leaves the keystore. */
@Singleton
class KeystoreSecretCipher @Inject constructor() : AesGcmSecretCipher(KeystoreKey::get)

private object KeystoreKey {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "seastreams_sources"
    private const val KEY_BITS = 256

    @Synchronized
    fun get(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build(),
        )
        return generator.generateKey()
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SecurityModule {
    @Binds
    abstract fun bindSecretCipher(impl: KeystoreSecretCipher): SecretCipher
}
