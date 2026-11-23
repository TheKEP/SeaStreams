package com.seastreams.tv.ui.screens.settings.sources

import com.seastreams.tv.data.usenet.NewznabIndexer
import com.seastreams.tv.data.usenet.UsenetProvider
import com.seastreams.tv.data.usenet.headers.HeaderPreset
import com.seastreams.tv.data.usenet.headers.HeaderUse
import com.seastreams.tv.data.usenet.headers.RequestHeaderSettings
import com.seastreams.tv.domain.usenet.UsenetPerformance
import com.seastreams.tv.domain.usenet.UsenetProfile
import com.seastreams.tv.ui.screens.settings.PORTAL_ROW_KEY
import com.seastreams.tv.ui.screens.settings.SettingsCallbacks
import com.seastreams.tv.ui.screens.settings.SettingsItem
import com.seastreams.tv.ui.screens.settings.SettingsPanel
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class SourcesItemsTest {

    private val zone = ZoneOffset.UTC
    private val now = LocalDateTime.of(2026, 11, 22, 18, 0).toInstant(zone).toEpochMilli()

    private fun provider(id: Long, enabled: Boolean = true) = UsenetProvider(
        id, "P$id", "news.p$id.com", 563, true, "me", hasPassword = true, passwordNeeded = false,
        maxConnections = 20, pipelineDepth = 2, priority = 0, isBackup = false, enabled = enabled,
    )

    private fun indexer(id: Long, enabled: Boolean = true) = NewznabIndexer(
        id, "I$id", "https://i$id.example", "/api", hasApiKey = true, apiKeyNeeded = false, priority = 0, backupOnly = false, enabled = enabled,
    )

    private fun items(
        state: SourcesUiState,
        open: (SettingsPanel) -> Unit = {},
        update: () -> Unit = {},
    ): List<SettingsItem.Entry> =
        sourcesItems(
            state,
            SettingsCallbacks({ open(it) }, {}, {}, mockk(relaxed = true), {}, {}, {}, {}, updateHeaders = update),
            now,
            zone,
        ).filterIsInstance<SettingsItem.Entry>()

    @Test
    fun `rows show counts, header versions and the profile`() {
        val state = SourcesUiState(
            providers = listOf(provider(1), provider(2, enabled = false)),
            indexers = listOf(indexer(1)),
            performance = UsenetPerformance.template(UsenetProfile.HIGH),
            headers = RequestHeaderSettings(versions = mapOf(HeaderPreset.SABNZBD to "5.2.0")),
        )
        assertEquals(
            listOf("1 of 2 enabled", "1 of 1 enabled", "Prowlarr 2.7.0.5723", "SABnzbd 5.2.0", "Never checked", "High", "Add providers and indexers from your phone"),
            items(state).map { it.value },
        )
        assertEquals("None yet — add one from your phone", items(SourcesUiState()).first().value)
    }

    @Test
    fun `rows open their panels, the portal and the header update`() {
        val opened = mutableListOf<SettingsPanel>()
        var updates = 0
        val rows = items(SourcesUiState(), open = { opened += it }, update = { updates++ })
        rows.forEach { it.onClick() }
        assertEquals(
            listOf(
                SettingsPanel.UsenetProviders, SettingsPanel.UsenetIndexers, SettingsPanel.Headers(HeaderUse.SEARCH),
                SettingsPanel.Headers(HeaderUse.DOWNLOAD), SettingsPanel.UsenetPerformance, SettingsPanel.Portal,
            ),
            opened,
        )
        assertEquals(1, updates)
        assertEquals(PORTAL_ROW_KEY, rows.last().key)
    }

    @Test
    fun `the header check label reads updating, never, today or the date`() {
        assertEquals("Updating…", headerCheckLabel(now, updating = true, now, zone))
        assertEquals("Never checked", headerCheckLabel(null, updating = false, now, zone))
        assertEquals("Checked today", headerCheckLabel(now - 17 * 3_600_000L, updating = false, now, zone))
        assertEquals("Last checked 3 Nov", headerCheckLabel(LocalDateTime.of(2026, 11, 3, 9, 0).toInstant(zone).toEpochMilli(), false, now, zone))
    }

    @Test
    fun `header pickers mark the default and the selection and save the pick`() {
        val picks = mutableListOf<Pair<HeaderUse, HeaderPreset>>()
        val spec = headerChoiceSpec(HeaderUse.DOWNLOAD, SourcesUiState(headers = RequestHeaderSettings(download = HeaderPreset.NZBGET))) { use, preset ->
            picks += use to preset
        }
        assertEquals(HeaderPreset.entries.size, spec.options.size)
        assertEquals("SABnzbd 5.1.3 (default)", spec.options.first { it.label.startsWith("SABnzbd") }.label)
        assertEquals(listOf("NZBGet 26.3"), spec.options.filter { it.selected }.map { it.label })
        spec.options.first { it.label.startsWith("Radarr") }.apply()
        assertEquals(listOf(HeaderUse.DOWNLOAD to HeaderPreset.RADARR), picks)
    }

    @Test
    fun `subtitles flag backup, missing secrets and disabled sources`() {
        assertEquals(
            "news.p1.com:563 · TLS · 20 connections · Backup · Password needed · Disabled",
            providerSubtitle(provider(1, enabled = false).copy(isBackup = true, passwordNeeded = true)),
        )
        assertEquals("i1.example/api · Backup only · API key needed", indexerSubtitle(indexer(1).copy(backupOnly = true, apiKeyNeeded = true)))
    }
}
