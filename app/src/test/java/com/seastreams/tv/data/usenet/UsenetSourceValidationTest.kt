package com.seastreams.tv.data.usenet

import com.seastreams.tv.data.UserFacingException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class UsenetSourceValidationTest {

    private val provider = ProviderDraft(name = "Eweka", host = "news.eweka.nl", username = "me", password = "pw")
    private val indexer = IndexerDraft(name = "NZBgeek", baseUrl = "https://api.nzbgeek.info", apiKey = "abc")

    private fun providerError(draft: ProviderDraft) =
        assertThrows<UserFacingException> { UsenetSourceValidation.provider(draft) }.message

    private fun indexerError(draft: IndexerDraft) =
        assertThrows<UserFacingException> { UsenetSourceValidation.indexer(draft) }.message

    @Test
    fun `line breaks can never reach an NNTP AUTHINFO command`() {
        assertEquals("The username can't contain line breaks", providerError(provider.copy(username = "me\r\nQUIT")))
        assertEquals("The password can't contain line breaks", providerError(provider.copy(password = "pw\nAUTHINFO")))
        assertEquals("That server address isn't valid", providerError(provider.copy(host = "news.eweka.nl\r\nQUIT")))
        assertEquals("The API key can't contain line breaks", indexerError(indexer.copy(apiKey = "a\rb")))
    }

    @Test
    fun `provider ranges follow PRD section 12`() {
        assertEquals("Give the provider a name", providerError(provider.copy(name = "  ")))
        assertEquals("Enter the provider's server address", providerError(provider.copy(host = " ")))
        assertEquals("Enter just the server address, e.g. news.example.com", providerError(provider.copy(host = "nntps://news.eweka.nl")))
        assertEquals("The port must be between 1 and 65535", providerError(provider.copy(port = 0)))
        assertEquals("Max connections must be between 1 and 100", providerError(provider.copy(maxConnections = 101)))
        assertEquals("Pipeline depth must be between 1 and 16", providerError(provider.copy(pipelineDepth = 17)))
        assertEquals("Pipeline depth must be between 1 and 16", providerError(provider.copy(pipelineDepth = 0)))
        assertEquals("Priority must be between 0 and 99", providerError(provider.copy(priority = 100)))
        listOf(1, 100).forEach { UsenetSourceValidation.provider(provider.copy(maxConnections = it)) }
        listOf(1, 16).forEach { UsenetSourceValidation.provider(provider.copy(pipelineDepth = it)) }
    }

    @Test
    fun `defaults are 563, TLS, 20 connections and pipeline depth 2`() {
        val draft = ProviderDraft(name = "X", host = "news.x.com")
        assertEquals(563, draft.port)
        assertEquals(true, draft.tls)
        assertEquals(20, draft.maxConnections)
        assertEquals(2, draft.pipelineDepth)
    }

    @Test
    fun `indexer addresses need http or https and keep a LAN port`() {
        assertEquals("Enter the indexer address, e.g. https://indexer.example", indexerError(indexer.copy(baseUrl = "api.nzbgeek.info")))
        assertEquals("Enter the indexer address, e.g. https://indexer.example", indexerError(indexer.copy(baseUrl = "ftp://x.com")))
        assertEquals("The API path must start with / (usually /api)", indexerError(indexer.copy(apiPath = "api")))

        val lan = UsenetSourceValidation.indexer(IndexerDraft(name = "Prowlarr", baseUrl = "http://192.168.1.10:9696", apiPath = "/1/api"))
        assertEquals("http://192.168.1.10:9696", lan.baseUrl)
        assertEquals("/1/api", lan.apiPath)
        val pasted = UsenetSourceValidation.indexer(indexer.copy(baseUrl = "https://www.tabula-rasa.pw/api/v1/api"))
        assertEquals("https://www.tabula-rasa.pw", pasted.baseUrl)
        assertEquals("/api/v1/api", pasted.apiPath)
    }

    @Test
    fun `moveId clamps to the list bounds`() {
        assertEquals(listOf(2L, 1L, 3L), moveId(listOf(1L, 2L, 3L), 2L, -1))
        assertEquals(listOf(1L, 2L, 3L), moveId(listOf(1L, 2L, 3L), 1L, -5))
        assertEquals(listOf(2L, 3L, 1L), moveId(listOf(1L, 2L, 3L), 1L, 5))
        assertEquals(listOf(1L, 2L), moveId(listOf(1L, 2L), 9L, 1))
    }
}
