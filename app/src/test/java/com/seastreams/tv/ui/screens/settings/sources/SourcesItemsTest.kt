package com.seastreams.tv.ui.screens.settings.sources

import androidx.compose.ui.input.key.Key
import com.seastreams.tv.data.debrid.DebridAuthMethod
import com.seastreams.tv.data.debrid.DebridCredentialState
import com.seastreams.tv.data.debrid.DebridProvider
import com.seastreams.tv.data.debrid.DebridServiceKind
import com.seastreams.tv.data.usenet.NewznabIndexer
import com.seastreams.tv.data.usenet.UsenetProvider
import com.seastreams.tv.data.usenet.headers.HeaderPreset
import com.seastreams.tv.data.usenet.headers.HeaderUse
import com.seastreams.tv.data.usenet.headers.RequestHeaderSettings
import com.seastreams.tv.domain.usenet.UsenetPerformance
import com.seastreams.tv.domain.usenet.UsenetProfile
import com.seastreams.tv.ui.screens.settings.AddonRemovalFocus
import com.seastreams.tv.ui.screens.settings.AddonRowControl
import com.seastreams.tv.ui.screens.settings.PORTAL_ROW_KEY
import com.seastreams.tv.ui.screens.settings.SettingsCallbacks
import com.seastreams.tv.ui.screens.settings.SettingsItem
import com.seastreams.tv.ui.screens.settings.SettingsPanel
import com.seastreams.tv.ui.screens.settings.SettingsRowEdge
import com.seastreams.tv.ui.screens.settings.addonRemovalFocus
import com.seastreams.tv.ui.screens.settings.addonsPanelLastIndex
import com.seastreams.tv.ui.screens.settings.firstEntryKey
import com.seastreams.tv.ui.screens.settings.lastEntryKey
import com.seastreams.tv.ui.screens.settings.settingsEntryOrdinals
import com.seastreams.tv.ui.screens.settings.settingsRowEdges
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

    private fun debridProvider(id: Long, kind: DebridServiceKind, state: DebridCredentialState, enabled: Boolean = true) =
        DebridProvider(id, kind, kind.displayName, DebridAuthMethod.API_KEY, state, enabled)

    private fun callbacks(open: (SettingsPanel) -> Unit = {}, update: () -> Unit = {}, actions: SourcesActions = SourcesActions()) =
        SettingsCallbacks({ open(it) }, {}, {}, mockk(relaxed = true), {}, {}, {}, {}, updateHeaders = update, sources = actions)

    private fun all(
        state: SourcesUiState = SourcesUiState(),
        debrid: DebridUiState = DebridUiState(),
        cb: SettingsCallbacks = callbacks(),
        nowMillis: Long = now,
    ): List<SettingsItem> = sourcesItems(state, cb, nowMillis, zone, debrid)

    private fun items(state: SourcesUiState = SourcesUiState(), debrid: DebridUiState = DebridUiState(), cb: SettingsCallbacks = callbacks()) =
        all(state, debrid, cb).filterIsInstance<SettingsItem.Entry>()

    @Test
    fun `Sources has USENET, DEBRID and PORTAL sections in the documented order`() {
        val layout = all().map { if (it is SettingsItem.Section) "[${it.title}]" else (it as SettingsItem.Entry).title }
        assertEquals(
            listOf(
                "[Usenet]", "Enable Usenet", "Usenet providers", "Indexers", "Search request headers", "Download request headers",
                "Update header versions", "Usenet performance", "Clear Usenet settings",
                "[Debrid]", "Enable Debrid", "Debrid providers", "Clear Debrid settings",
                "[Portal]", "Web portal",
            ),
            layout,
        )
    }

    @Test
    fun `the one-way wrap spans the whole tab and skips subheadings`() {
        val list = all()
        val ordinals = settingsEntryOrdinals(list)
        val lastOrdinal = ordinals.max()
        assertEquals(USENET_ENABLED_ROW_KEY, firstEntryKey(list))
        assertEquals(PORTAL_ROW_KEY, lastEntryKey(list))
        // Subheadings are never focus targets.
        list.forEachIndexed { index, item -> if (item is SettingsItem.Section) assertEquals(-1, ordinals[index]) }
        // A fresh Down on Web portal wraps to Enable Usenet; Up on Enable Usenet goes to the pill (never the bottom).
        val portal = ordinals[list.indexOfFirst { it.key == PORTAL_ROW_KEY }]
        val enable = ordinals[list.indexOfFirst { it.key == USENET_ENABLED_ROW_KEY }]
        assertEquals(mapOf(Key.DirectionDown to SettingsRowEdge.WRAP_TO_FIRST), settingsRowEdges(portal, lastOrdinal))
        assertEquals(mapOf(Key.DirectionUp to SettingsRowEdge.TO_TAB_BAR), settingsRowEdges(enable, lastOrdinal))
        // Section boundaries are ordinary moves: Clear Usenet settings → Enable Debrid owns no edge.
        val clearUsenet = ordinals[list.indexOfFirst { it.key == USENET_CLEAR_ROW_KEY }]
        assertEquals(emptyMap<Key, SettingsRowEdge>(), settingsRowEdges(clearUsenet, lastOrdinal))
    }

    @Test
    fun `rows show toggles, counts, header versions and the profile`() {
        val state = SourcesUiState(
            providers = listOf(provider(1), provider(2, enabled = false)),
            indexers = listOf(indexer(1)),
            performance = UsenetPerformance.template(UsenetProfile.HIGH),
            headers = RequestHeaderSettings(versions = mapOf(HeaderPreset.SABNZBD to "5.2.0")),
            enabled = true,
        )
        val debrid = DebridUiState(
            providers = listOf(debridProvider(1, DebridServiceKind.REAL_DEBRID, DebridCredentialState.SIGNED_IN), debridProvider(2, DebridServiceKind.TORBOX, DebridCredentialState.API_KEY, enabled = false)),
        )
        assertEquals(
            listOf(
                "On: Usenet results are searched", "1 of 2 enabled", "1 of 1 enabled", "Prowlarr 2.7.0.5723", "SABnzbd 5.2.0",
                "Never checked", "High", "Removes every Usenet provider, indexer and setting",
                "Off: Debrid is not searched — your providers are kept", "1 of 2 enabled", "Removes every Debrid provider and its keys",
                PORTAL_HINT,
            ),
            items(state, debrid).map { it.value },
        )
        val empty = items()
        assertEquals("Off: Usenet is not searched — your providers and settings are kept", empty[0].value)
        assertEquals("None yet — add one from your phone", empty.first { it.key == PROVIDERS_ROW_KEY }.value)
        assertEquals("None yet — add one from your phone", empty.first { it.key == DEBRID_PROVIDERS_ROW_KEY }.value)
        assertEquals("On: your Debrid libraries are searched", items(debrid = DebridUiState(enabled = true)).first { it.key == DEBRID_ENABLED_ROW_KEY }.value)
    }

    @Test
    fun `rows open their panels, the portal and the header update`() {
        val opened = mutableListOf<SettingsPanel>()
        var updates = 0
        val rows = items(cb = callbacks(open = { opened += it }, update = { updates++ }))
        rows.filter { it.key !in setOf(USENET_ENABLED_ROW_KEY, DEBRID_ENABLED_ROW_KEY, USENET_CLEAR_ROW_KEY, DEBRID_CLEAR_ROW_KEY) }.forEach { it.onClick() }
        assertEquals(
            listOf(
                SettingsPanel.UsenetProviders, SettingsPanel.UsenetIndexers, SettingsPanel.Headers(HeaderUse.SEARCH),
                SettingsPanel.Headers(HeaderUse.DOWNLOAD), SettingsPanel.UsenetPerformance, SettingsPanel.DebridProviders, SettingsPanel.Portal,
            ),
            opened,
        )
        assertEquals(1, updates)
        assertEquals(PORTAL_ROW_KEY, rows.last().key)
    }

    @Test
    fun `each toggle and clear row only acts on its own source`() {
        val calls = mutableListOf<String>()
        val actions = SourcesActions(
            setUsenetEnabled = { calls += "usenet=$it" },
            clearUsenet = { calls += "clear usenet" },
            undoClearUsenet = { calls += "undo usenet" },
            setDebridEnabled = { calls += "debrid=$it" },
            clearDebrid = { calls += "clear debrid" },
            undoClearDebrid = { calls += "undo debrid" },
        )
        val rows = items(SourcesUiState(enabled = true), DebridUiState(enabled = false), callbacks(actions = actions)).associateBy { it.key }
        listOf(USENET_ENABLED_ROW_KEY, USENET_CLEAR_ROW_KEY, DEBRID_ENABLED_ROW_KEY, DEBRID_CLEAR_ROW_KEY).forEach { rows.getValue(it).onClick() }
        assertEquals(listOf("usenet=false", "clear usenet", "debrid=true", "clear debrid"), calls)
    }

    @Test
    fun `a pending clear flips only its own row to Undo with a countdown`() {
        val calls = mutableListOf<String>()
        val actions = SourcesActions(undoClearUsenet = { calls += "undo usenet" }, undoClearDebrid = { calls += "undo debrid" })
        val usenetPending = items(SourcesUiState(clearDeadlineMillis = now + 4_200), cb = callbacks(actions = actions)).associateBy { it.key }
        assertEquals("Undo clear Usenet settings", usenetPending.getValue(USENET_CLEAR_ROW_KEY).title)
        assertEquals("Usenet settings will be cleared in 5s", usenetPending.getValue(USENET_CLEAR_ROW_KEY).value)
        assertEquals("Clear Debrid settings", usenetPending.getValue(DEBRID_CLEAR_ROW_KEY).title)
        usenetPending.getValue(USENET_CLEAR_ROW_KEY).onClick()

        val both = items(SourcesUiState(clearDeadlineMillis = now + 1_000), DebridUiState(clearDeadlineMillis = now + 3_000), callbacks(actions = actions))
            .associateBy { it.key }
        assertEquals("Undo clear Usenet settings", both.getValue(USENET_CLEAR_ROW_KEY).title)
        assertEquals("Undo clear Debrid settings", both.getValue(DEBRID_CLEAR_ROW_KEY).title)
        assertEquals("Debrid settings will be cleared in 3s", both.getValue(DEBRID_CLEAR_ROW_KEY).value)
        both.getValue(DEBRID_CLEAR_ROW_KEY).onClick()
        assertEquals(listOf("undo usenet", "undo debrid"), calls)
        assertEquals(0, secondsLeft(now - 50, now))
    }

    @Test
    fun `Debrid rows show the credential state, never the key, and follow the Manage addons edges`() {
        assertEquals("API key", debridProviderSubtitle(debridProvider(1, DebridServiceKind.PREMIUMIZE, DebridCredentialState.API_KEY)))
        assertEquals("Signed in", debridProviderSubtitle(debridProvider(1, DebridServiceKind.REAL_DEBRID, DebridCredentialState.SIGNED_IN)))
        assertEquals("Sign-in needed", debridProviderSubtitle(debridProvider(1, DebridServiceKind.TORBOX, DebridCredentialState.SIGN_IN_NEEDED)))
        // Three providers + `Add a provider`: the wrap runs between row 0 and index 3; removing the last row lands on Add.
        assertEquals(3, addonsPanelLastIndex(3))
        assertEquals(AddonRemovalFocus.Row(2, AddonRowControl.REMOVE), addonRemovalFocus(1, 3) { true })
        assertEquals(AddonRemovalFocus.InstallMore, addonRemovalFocus(2, 3) { true })
        assertEquals(0, addonsPanelLastIndex(0))
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
