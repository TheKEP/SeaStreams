package com.seastreams.tv.ui.screens.settings.sources

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import com.seastreams.tv.ui.screens.settings.ChoicePanel
import com.seastreams.tv.ui.screens.settings.SettingsPanel

/**
 * The `Sources` sub-panels (PRD §11): `Usenet providers`, `Indexers` and `Debrid providers` on the shared
 * [SourceListPanel] (Manage addons focus rules, `Add …` → Web portal), the header pickers and `Usenet performance`.
 * Usenet panels only call [usenet]; the Debrid panel only calls [debrid].
 */
@Composable
fun SourcesPanelHost(
    panel: SettingsPanel,
    usenetState: SourcesUiState,
    usenet: SourcesViewModel,
    debridState: DebridUiState,
    debrid: DebridSourcesViewModel,
    openPortal: () -> Unit,
    close: () -> Unit,
) {
    when (panel) {
        SettingsPanel.UsenetProviders -> SourceListPanel(
            title = "Usenet providers",
            icon = Icons.Filled.Lock,
            rows = usenetState.providers.map { SourceRow(it.id, it.name, providerSubtitle(it), it.enabled) },
            addTitle = "Add a provider",
            addHint = "Enter the server and login in the Web Portal",
            onToggle = { usenet.setProviderEnabled(it.id, !it.enabled) },
            onMove = { row, offset -> usenet.moveProvider(row.id, offset) },
            onRemove = { usenet.removeProvider(it.id) },
            onAdd = openPortal,
            onTest = { usenet.testProvider(it.id) },
        )
        SettingsPanel.UsenetIndexers -> SourceListPanel(
            title = "Indexers",
            icon = Icons.Filled.Search,
            rows = usenetState.indexers.map { SourceRow(it.id, it.name, indexerSubtitle(it), it.enabled) },
            addTitle = "Add an indexer",
            addHint = "Enter the indexer address and API key in the Web Portal",
            onToggle = { usenet.setIndexerEnabled(it.id, !it.enabled) },
            onMove = { row, offset -> usenet.moveIndexer(row.id, offset) },
            onRemove = { usenet.removeIndexer(it.id) },
            onAdd = openPortal,
            onTest = { usenet.testIndexer(it.id) },
        )
        SettingsPanel.DebridProviders -> SourceListPanel(
            title = "Debrid providers",
            icon = Icons.Filled.Lock,
            rows = debridState.providers.map { SourceRow(it.id, it.name, debridProviderSubtitle(it), it.enabled) },
            addTitle = "Add a provider",
            addHint = "Sign in with a code or paste an API key in the Web Portal",
            onToggle = { debrid.setProviderEnabled(it.id, !it.enabled) },
            onMove = { row, offset -> debrid.move(row.id, offset) },
            onRemove = { debrid.remove(it.id) },
            onAdd = openPortal,
            onTest = { debrid.test(it.id) },
        )
        SettingsPanel.UsenetPerformance -> UsenetPerformancePanel(
            performanceItems(usenetState.performance, usenet::setProfile, usenet::editPerformance),
        )
        is SettingsPanel.Headers -> ChoicePanel(headerChoiceSpec(panel.use, usenetState, usenet::setHeaderPreset), close)
        else -> Unit
    }
}
