package com.seastreams.tv.data.usenet

import com.seastreams.tv.data.UserFacingException
import com.seastreams.tv.testutil.FakeNewznabIndexerDao
import com.seastreams.tv.testutil.FakeSecretCipher
import com.seastreams.tv.testutil.FakeUsenetProviderDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class UsenetSourcesRepositoryTest {

    private val providerDao = FakeUsenetProviderDao()
    private val indexerDao = FakeNewznabIndexerDao()
    private val cipher = FakeSecretCipher()
    private val repository = UsenetSourcesRepository(providerDao, indexerDao, cipher, Dispatchers.Unconfined)

    private val eweka = ProviderDraft(name = " Eweka ", host = " News.Eweka.NL ", username = "me", password = "s3cret", maxConnections = 50)

    @Test
    fun `a saved provider stores only the encrypted password and never exposes it`() = runTest {
        repository.saveProvider(null, eweka)

        val row = providerDao.rows.value.single()
        assertEquals("Eweka", row.name)
        assertEquals("news.eweka.nl", row.host)
        assertEquals(cipher.encrypt("s3cret"), row.passwordCipher)
        assertFalse(row.passwordCipher.orEmpty().contains("s3cret"))
        val shown = repository.providers.first().single()
        assertTrue(shown.hasPassword)
        assertFalse(shown.passwordNeeded)
    }

    @Test
    fun `editing with a blank password keeps the stored one and a new one replaces it`() = runTest {
        val id = repository.saveProvider(null, eweka)
        repository.saveProvider(id, eweka.copy(name = "Eweka NL", password = ""))
        assertEquals(cipher.encrypt("s3cret"), providerDao.rows.value.single().passwordCipher)
        assertEquals("Eweka NL", providerDao.rows.value.single().name)

        repository.saveProvider(id, eweka.copy(password = null))
        assertEquals(cipher.encrypt("s3cret"), providerDao.rows.value.single().passwordCipher)

        repository.saveProvider(id, eweka.copy(password = "new"))
        assertEquals(cipher.encrypt("new"), providerDao.rows.value.single().passwordCipher)
    }

    @Test
    fun `a password the device can no longer decrypt shows as needed`() = runTest {
        repository.saveProvider(null, eweka)
        cipher.broken = true
        assertTrue(repository.providers.first().single().passwordNeeded)
    }

    @Test
    fun `a provider without a password stores none`() = runTest {
        repository.saveProvider(null, eweka.copy(password = null))
        assertNull(providerDao.rows.value.single().passwordCipher)
        assertFalse(repository.providers.first().single().hasPassword)
    }

    @Test
    fun `providers append in order, move within bounds and stay dense after removal`() = runTest {
        val a = repository.saveProvider(null, eweka.copy(name = "A"))
        val b = repository.saveProvider(null, eweka.copy(name = "B"))
        val c = repository.saveProvider(null, eweka.copy(name = "C"))
        assertEquals(listOf("A", "B", "C"), repository.providers.first().map { it.name })

        repository.moveProvider(c, -1)
        assertEquals(listOf("A", "C", "B"), repository.providers.first().map { it.name })
        repository.moveProvider(a, -1)
        assertEquals(listOf("A", "C", "B"), repository.providers.first().map { it.name })

        repository.removeProvider(a)
        assertEquals(listOf("C", "B"), repository.providers.first().map { it.name })
        assertEquals(listOf(0, 1), providerDao.getAll().map { it.sortOrder })
        repository.setProviderEnabled(b, false)
        assertEquals(listOf(true, false), repository.providers.first().map { it.enabled })
    }

    @Test
    fun `unknown ids are rejected with a readable message`() = runTest {
        assertEquals("Provider not found", assertThrows<UserFacingException> { repository.removeProvider(9) }.message)
        assertEquals("Provider not found", assertThrows<UserFacingException> { repository.saveProvider(9, eweka) }.message)
        assertEquals("Indexer not found", assertThrows<UserFacingException> { repository.moveIndexer(9, 1) }.message)
        assertEquals("Indexer not found", assertThrows<UserFacingException> { repository.setIndexerEnabled(9, true) }.message)
    }

    @Test
    fun `indexers encrypt the API key, keep it on blank edits and normalise a pasted API URL`() = runTest {
        val id = repository.saveIndexer(null, IndexerDraft(name = "NZBgeek", baseUrl = "https://API.nzbgeek.info/api/", apiKey = " key123 "))
        val row = indexerDao.rows.value.single()
        assertEquals("https://api.nzbgeek.info", row.baseUrl)
        assertEquals("/api", row.apiPath)
        assertEquals(cipher.encrypt("key123"), row.apiKeyCipher)

        repository.saveIndexer(id, IndexerDraft(name = "Geek", baseUrl = row.baseUrl, apiKey = ""))
        assertEquals(cipher.encrypt("key123"), indexerDao.rows.value.single().apiKeyCipher)
        assertTrue(repository.indexers.first().single().hasApiKey)

        repository.removeIndexer(id)
        assertTrue(repository.indexers.first().isEmpty())
    }

    @Test
    fun `endpoints carry the decrypted password for the engine and skip unusable providers`() = runTest {
        val id = repository.saveProvider(null, eweka)
        repository.saveProvider(null, eweka.copy(name = "Off", enabled = false))
        repository.saveProvider(null, eweka.copy(name = "Nopass", password = null))
        val endpoints = repository.enabledEndpoints()
        assertEquals(listOf("Eweka"), endpoints.map { it.name })
        assertEquals("s3cret", endpoints.single().password)
        assertEquals(id, endpoints.single().id)
        assertFalse(endpoints.single().toString().contains("s3cret"))
        assertEquals("s3cret", repository.endpoint(id).password)

        cipher.broken = true
        assertTrue(repository.enabledEndpoints().isEmpty())
        assertEquals(
            "Password needed: enter it again in the Web Portal",
            assertThrows<UserFacingException> { repository.endpoint(id) }.message,
        )
    }

    @Test
    fun `a portal draft tests with the typed password, or the stored one when left blank`() = runTest {
        val id = repository.saveProvider(null, eweka)
        assertEquals("typed", repository.endpointForDraft(id, eweka.copy(password = "typed")).password)
        assertEquals("s3cret", repository.endpointForDraft(id, eweka.copy(password = "")).password)
        assertEquals("news.eweka.nl", repository.endpointForDraft(null, eweka).host)
        assertThrows<UserFacingException> { repository.endpointForDraft(null, eweka.copy(password = null)) }
        assertThrows<UserFacingException> { repository.endpointForDraft(null, eweka.copy(host = "")) }
    }
}
