package com.seastreams.tv.core.util

import com.seastreams.tv.core.webserver.DebridProviderRequest
import com.seastreams.tv.core.webserver.DebridTestRequest
import com.seastreams.tv.core.webserver.IndexerRequest
import com.seastreams.tv.core.webserver.ProviderRequest
import com.seastreams.tv.core.webserver.TmdbKeyRequest
import com.seastreams.tv.data.backup.AppBackup
import com.seastreams.tv.data.backup.DebridBackup
import com.seastreams.tv.data.backup.DebridProviderBackup
import com.seastreams.tv.data.backup.IndexerBackup
import com.seastreams.tv.data.backup.NntpProviderBackup
import com.seastreams.tv.data.backup.UsenetBackup
import com.seastreams.tv.data.setup.DebugSeedConfig
import com.seastreams.tv.data.usenet.IndexerDraft
import com.seastreams.tv.data.usenet.ProviderDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Models that carry a password, API key, token or client secret never print it (they may reach a log or an error). */
class SecretToStringTest {

    @Test
    fun `secret-bearing models print whether a secret is set, never the secret`() {
        val nntp = NntpProviderBackup("News", "news.example", 563, true, "user", SECRET, 20, 2, 0)
        val indexer = IndexerBackup("Indexer", "https://indexer.example", "/api", SECRET, 0)
        val models = listOf(
            TmdbKeyRequest(SECRET),
            ProviderRequest(name = "News", host = "news.example", password = SECRET),
            IndexerRequest(name = "Indexer", baseUrl = "https://indexer.example", apiKey = SECRET),
            DebridProviderRequest(kind = "REAL_DEBRID", apiKey = SECRET),
            DebridTestRequest(kind = "TORBOX", apiKey = SECRET),
            ProviderDraft(name = "News", host = "news.example", password = SECRET),
            IndexerDraft(name = "Indexer", baseUrl = "https://indexer.example", apiKey = SECRET),
            nntp,
            indexer,
            DebridProviderBackup("REAL_DEBRID", "Real-Debrid", "DEVICE", credential = SECRET, refreshToken = SECRET, clientId = SECRET, clientSecret = SECRET),
            AppBackup(
                version = 1, scope = "app", includesCredentials = true, tmdbApiKey = SECRET,
                usenet = UsenetBackup(providers = listOf(nntp), indexers = listOf(indexer)),
                debrid = DebridBackup(providers = listOf(DebridProviderBackup("TORBOX", "TorBox", "API_KEY", credential = SECRET))),
                profiles = emptyList(),
            ),
            DebugSeedConfig(enabled = true, tmdbApiKey = SECRET),
        )
        models.forEach { model ->
            val text = model.toString()
            assertFalse(text.contains(SECRET), text)
            assertTrue(text.contains("***"), text)
        }
    }

    @Test
    fun `redacted tells absent, empty and set apart`() {
        assertEquals("null", redacted(null))
        assertEquals("\"\"", redacted(""))
        assertEquals("***", redacted(SECRET))
    }

    private companion object {
        const val SECRET = "s3cr3t-value-9f2c"
    }
}
