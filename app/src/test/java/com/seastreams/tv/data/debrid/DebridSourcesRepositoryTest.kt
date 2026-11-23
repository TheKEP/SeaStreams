package com.seastreams.tv.data.debrid

import com.seastreams.tv.data.UserFacingException
import com.seastreams.tv.data.debrid.api.DebridAuthException
import com.seastreams.tv.data.debrid.auth.DeviceTokens
import com.seastreams.tv.testutil.FakeDebridProviderDao
import com.seastreams.tv.testutil.FakeSecretCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DebridSourcesRepositoryTest {

    private val dao = FakeDebridProviderDao()
    private val cipher = FakeSecretCipher()
    private val repository = DebridSourcesRepository(dao, cipher, Dispatchers.Unconfined)

    @Test
    fun `a pasted key is stored only encrypted and shown as API key`() = runTest {
        val id = repository.saveKey(null, DebridServiceKind.TORBOX, "  tb-key-0123456789  ", null)

        val row = dao.rows.value.single()
        assertEquals(cipher.encrypt("tb-key-0123456789"), row.credentialCipher)
        assertFalse(row.toString().contains("tb-key-0123456789"))
        val provider = repository.providers.first().single()
        assertEquals(DebridProvider(id, DebridServiceKind.TORBOX, "TorBox", DebridAuthMethod.API_KEY, DebridCredentialState.API_KEY, true), provider)
        assertEquals("tb-key-0123456789", repository.credentials(id).accessToken)
    }

    @Test
    fun `keys with line breaks, spaces, control characters or a bad length are refused`() = runTest {
        listOf("abc\r\ndefghijk", "abcd efgh ijkl", "abcdefgh\u0007ij", "short", "k".repeat(4097), "tab\tbedkeyvalue").forEach { key ->
            val error = assertThrows<UserFacingException> { repository.saveKey(null, DebridServiceKind.REAL_DEBRID, key, null) }
            assertEquals("Enter a valid Real-Debrid API token", error.message)
        }
        assertTrue(dao.rows.value.isEmpty())
        assertThrows<UserFacingException> { repository.saveKey(null, DebridServiceKind.REAL_DEBRID, "token-123456", "bad\nname") }
        assertEquals("Enter your Premiumize API key", assertThrows<UserFacingException> { repository.saveKey(null, DebridServiceKind.PREMIUMIZE, "  ", null) }.message)
    }

    @Test
    fun `editing with a blank key keeps the stored credential and only renames`() = runTest {
        val id = repository.saveKey(null, DebridServiceKind.ALL_DEBRID, "ad-key-0123456789", null)
        val stored = dao.rows.value.single().credentialCipher

        repository.saveKey(id, DebridServiceKind.ALL_DEBRID, "", "My AllDebrid")
        assertEquals(stored, dao.rows.value.single().credentialCipher)
        assertEquals("My AllDebrid", dao.rows.value.single().name)

        repository.saveKey(id, DebridServiceKind.ALL_DEBRID, "ad-key-NEW-012345", "My AllDebrid")
        assertEquals("ad-key-NEW-012345", repository.credentials(id).accessToken)
    }

    @Test
    fun `there is one account per service`() = runTest {
        repository.saveKey(null, DebridServiceKind.OFFCLOUD, "oc-key-0123456789", null)
        val error = assertThrows<UserFacingException> { repository.saveKey(null, DebridServiceKind.OFFCLOUD, "oc-key-other-0123", null) }
        assertEquals("Offcloud is already added. Edit it instead", error.message)
        val id = dao.rows.value.single().id
        assertThrows<UserFacingException> { repository.saveKey(id, DebridServiceKind.TORBOX, "tb-key-0123456789", null) }
    }

    @Test
    fun `an undecryptable or revoked credential reads Sign-in needed and is skipped by searches`() = runTest {
        val a = repository.saveKey(null, DebridServiceKind.TORBOX, "tb-key-0123456789", null)
        val b = repository.saveKey(null, DebridServiceKind.PREMIUMIZE, "pm-key-0123456789", null)
        repository.markSignInNeeded(b, true)
        assertEquals(listOf(DebridCredentialState.API_KEY, DebridCredentialState.SIGN_IN_NEEDED), repository.providers.first().map { it.credentialState })
        assertEquals(listOf(a), repository.enabledCredentials().map { it.providerId })

        cipher.broken = true
        assertTrue(repository.providers.first().all { it.credentialState == DebridCredentialState.SIGN_IN_NEEDED })
        assertTrue(repository.enabledCredentials().isEmpty())
        assertThrows<DebridAuthException> { repository.credentials(a) }
    }

    @Test
    fun `enable, move and remove keep a dense order`() = runTest {
        val rd = repository.saveKey(null, DebridServiceKind.REAL_DEBRID, "rd-key-0123456789", null)
        val tb = repository.saveKey(null, DebridServiceKind.TORBOX, "tb-key-0123456789", null)
        val pm = repository.saveKey(null, DebridServiceKind.PREMIUMIZE, "pm-key-0123456789", null)

        repository.move(pm, -1)
        assertEquals(listOf(rd, pm, tb), repository.providers.first().map { it.id })
        repository.setEnabled(rd, false)
        assertFalse(repository.providers.first().first().enabled)
        repository.remove(pm)
        assertEquals(listOf(rd to 0, tb to 1), dao.getAll().map { it.id to it.sortOrder })
        assertThrows<UserFacingException> { repository.remove(pm) }
    }

    @Test
    fun `a device sign-in is stored encrypted, keeps the row's place, and expires from now`() = runTest {
        val pm = repository.saveKey(null, DebridServiceKind.PREMIUMIZE, "pm-key-0123456789", null)
        val rd = repository.saveKey(null, DebridServiceKind.REAL_DEBRID, "rd-key-0123456789", "Mine")
        repository.setEnabled(rd, false)
        val generation = repository.generation(DebridServiceKind.REAL_DEBRID)

        val saved = repository.saveDeviceSignIn(
            DebridServiceKind.REAL_DEBRID,
            DeviceTokens("access-1", "refresh-1", "client-id", "client-secret", expiresInSeconds = 3_600),
            generation,
            nowMillis = 1_000,
        )

        assertEquals(rd, saved)
        val row = checkNotNull(dao.get(rd))
        assertEquals(listOf("Mine", "DEVICE", "false", "1"), listOf(row.name, row.authMethod, row.enabled.toString(), row.sortOrder.toString()))
        assertEquals(3_601_000L, row.expiresAtMillis)
        listOf(row.credentialCipher, row.refreshTokenCipher, row.clientIdCipher, row.clientSecretCipher).forEach { assertTrue(checkNotNull(it).startsWith("enc:")) }
        val credentials = repository.credentials(rd)
        assertEquals(listOf("access-1", "refresh-1", "client-id", "client-secret"), listOf(credentials.accessToken, credentials.refreshToken, credentials.clientId, credentials.clientSecret))
        assertFalse(credentials.toString().contains("access-1"))
        assertEquals(DebridCredentialState.SIGNED_IN, repository.providers.first().first { it.id == rd }.credentialState)
        assertEquals(pm, dao.rows.value.first { it.sortOrder == 0 }.id)
    }

    @Test
    fun `a sign-in that completes after a remove, a replace or a clear is discarded`() = runTest {
        val kind = DebridServiceKind.TORBOX
        val tokens = DeviceTokens("tb-access-0123")

        val beforeRemove = repository.generation(kind)
        val id = repository.saveKey(null, kind, "tb-key-0123456789", null)
        repository.remove(id)
        assertNull(repository.saveDeviceSignIn(kind, tokens, beforeRemove, 0))

        val beforeClear = repository.generation(kind)
        repository.deleteAll()
        assertNull(repository.saveDeviceSignIn(kind, tokens, beforeClear, 0))
        assertTrue(dao.rows.value.isEmpty())

        val current = repository.generation(kind)
        assertEquals(1L, repository.saveDeviceSignIn(kind, tokens, current, 0)?.let { 1L })
        assertNull(repository.saveDeviceSignIn(kind, tokens, current, 0), "a second completion of the same code can't land")
    }
}
