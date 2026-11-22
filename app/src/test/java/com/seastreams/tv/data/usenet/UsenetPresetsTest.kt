package com.seastreams.tv.data.usenet

import com.seastreams.tv.data.usenet.headers.HeaderPreset
import com.seastreams.tv.data.usenet.headers.RequestHeaderSettings
import com.seastreams.tv.data.usenet.headers.releaseVersion
import com.seastreams.tv.data.usenet.presets.IndexerPresets
import com.seastreams.tv.data.usenet.presets.UsenetProviderPresets
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UsenetPresetsTest {

    @Test
    fun `29 provider presets, unique and valid as drafts`() {
        assertEquals(29, UsenetProviderPresets.ALL.size)
        assertEquals(UsenetProviderPresets.ALL.size, UsenetProviderPresets.ALL.map { it.name }.toSet().size)
        UsenetProviderPresets.ALL.forEach { preset ->
            assertEquals(563, preset.port, preset.name)
            assertTrue(preset.tls, preset.name)
            UsenetSourceValidation.provider(ProviderDraft(preset.name, preset.host, preset.port, preset.tls, maxConnections = preset.maxConnections))
        }
    }

    @Test
    fun `indexer presets are unique, valid and keep their API paths`() {
        assertEquals(30, IndexerPresets.ALL.size)
        assertEquals(IndexerPresets.ALL.size, IndexerPresets.ALL.map { it.name }.toSet().size)
        IndexerPresets.ALL.forEach { preset ->
            val valid = UsenetSourceValidation.indexer(IndexerDraft(preset.name, preset.baseUrl, preset.apiPath))
            assertEquals(preset.baseUrl, valid.baseUrl, preset.name)
            assertEquals(preset.apiPath, valid.apiPath, preset.name)
        }
        assertEquals("/api/v1/api", IndexerPresets.ALL.single { it.name == "Tabula Rasa" }.apiPath)
    }

    @Test
    fun `Search defaults to Prowlarr and Download to SABnzbd with the built-in versions`() {
        val defaults = RequestHeaderSettings()
        assertEquals(HeaderPreset.PROWLARR, defaults.search)
        assertEquals(HeaderPreset.SABNZBD, defaults.download)
        assertEquals("Prowlarr 2.7.0.5723", defaults.search.displayLabel(defaults.versions))
        assertEquals("SABnzbd 5.1.3", defaults.download.displayLabel(defaults.versions))
        assertEquals(
            mapOf("User-Agent" to "Prowlarr/2.7.0.5723 (alpine 3.24.4)", "Accept" to "application/rss+xml, text/rss+xml, application/xml, text/xml"),
            HeaderPreset.PROWLARR.headers(emptyMap()),
        )
        assertEquals(mapOf("User-Agent" to "SABnzbd/5.1.3", "Accept" to "*/*"), HeaderPreset.SABNZBD.headers(emptyMap()))
    }

    @Test
    fun `fetched versions replace the fallback in the header`() {
        val fetched = mapOf(HeaderPreset.SABNZBD to "5.2.0")
        assertEquals("SABnzbd/5.2.0", HeaderPreset.SABNZBD.headers(fetched)["User-Agent"])
        assertEquals("Sonarr/4.0.20.3014 (alpine 3.24.4)", HeaderPreset.SONARR.headers(fetched)["User-Agent"])
        assertEquals(6, HeaderPreset.UPDATABLE.size)
    }

    @Test
    fun `only plain release versions are accepted from GitHub tags`() {
        assertEquals("2.7.0.5723", releaseVersion("v2.7.0.5723"))
        assertEquals("5.1.3", releaseVersion("5.1.3"))
        assertEquals("26.3", releaseVersion(" v26.3 "))
        listOf(null, "", "latest", "v2", "5.1.3-beta1", "5.1.3\r\nX-Evil: 1", "1.2.3.4.5").forEach {
            assertNull(releaseVersion(it), "$it")
        }
    }
}
