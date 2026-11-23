package com.seastreams.tv.ui.screens.settings.sources

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import com.seastreams.tv.data.usenet.NewznabIndexer
import com.seastreams.tv.data.usenet.UsenetProvider
import com.seastreams.tv.data.usenet.headers.HeaderPreset
import com.seastreams.tv.data.usenet.headers.HeaderUse
import com.seastreams.tv.ui.components.SeaIcons
import com.seastreams.tv.ui.screens.settings.ChoiceOption
import com.seastreams.tv.ui.screens.settings.ChoiceSpec
import com.seastreams.tv.ui.screens.settings.PORTAL_ROW_KEY
import com.seastreams.tv.ui.screens.settings.RowTrailing
import com.seastreams.tv.ui.screens.settings.SettingsCallbacks
import com.seastreams.tv.ui.screens.settings.SettingsItem
import com.seastreams.tv.ui.screens.settings.SettingsPanel
import com.seastreams.tv.ui.screens.settings.entry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The `Sources` rows (PRD §11) under their `USENET` subheading, in the exact documented order. */
internal fun sourcesItems(
    state: SourcesUiState,
    cb: SettingsCallbacks,
    nowMillis: Long,
    zone: ZoneId,
): List<SettingsItem> {
    val versions = state.headers.versions
    return listOf(
        // Room for a future `DEBRID LIBRARY` section below.
        SettingsItem.Section(USENET_SECTION_KEY, "Usenet"),
        entry(PROVIDERS_ROW_KEY, Icons.Filled.Lock, "Usenet providers", providersLabel(state.providers), RowTrailing.Chevron) {
            cb.open(SettingsPanel.UsenetProviders)
        },
        entry(INDEXERS_ROW_KEY, Icons.Filled.Search, "Indexers", indexersLabel(state.indexers), RowTrailing.Chevron) {
            cb.open(SettingsPanel.UsenetIndexers)
        },
        entry(HEADERS_SEARCH_ROW_KEY, SeaIcons.Language, HeaderUse.SEARCH.title, state.headers.search.displayLabel(versions), RowTrailing.Chevron) {
            cb.open(SettingsPanel.Headers(HeaderUse.SEARCH))
        },
        entry(HEADERS_DOWNLOAD_ROW_KEY, SeaIcons.Language, HeaderUse.DOWNLOAD.title, state.headers.download.displayLabel(versions), RowTrailing.Chevron) {
            cb.open(SettingsPanel.Headers(HeaderUse.DOWNLOAD))
        },
        entry(
            HEADERS_UPDATE_ROW_KEY, Icons.Filled.Refresh, "Update header versions",
            headerCheckLabel(state.headers.checkedAtMillis, state.updatingHeaders, nowMillis, zone), RowTrailing.None,
            onClick = cb.updateHeaders,
        ),
        entry(PERFORMANCE_ROW_KEY, Icons.Filled.Settings, "Usenet performance", state.performance.profile.label, RowTrailing.Chevron) {
            cb.open(SettingsPanel.UsenetPerformance)
        },
        entry(PORTAL_ROW_KEY, SeaIcons.QrCode, "Web portal", "Add providers, indexers, regexes and templates from your phone", RowTrailing.Chevron) {
            cb.open(SettingsPanel.Portal)
        },
    )
}

/** Search/Download header picker: every preset with its current version; picking one saves it. */
internal fun headerChoiceSpec(use: HeaderUse, state: SourcesUiState, onPick: (HeaderUse, HeaderPreset) -> Unit): ChoiceSpec {
    val selected = state.headers.presetFor(use)
    val default = if (use == HeaderUse.SEARCH) HeaderPreset.DEFAULT_SEARCH else HeaderPreset.DEFAULT_DOWNLOAD
    return ChoiceSpec(
        use.title,
        HeaderPreset.entries.map { preset ->
            val label = preset.displayLabel(state.headers.versions)
            ChoiceOption(if (preset == default) "$label (default)" else label, preset == selected) { onPick(use, preset) }
        },
    )
}

internal fun providersLabel(providers: List<UsenetProvider>): String =
    if (providers.isEmpty()) "None yet — add one from your phone" else "${providers.count { it.enabled }} of ${providers.size} enabled"

internal fun indexersLabel(indexers: List<NewznabIndexer>): String =
    if (indexers.isEmpty()) "None yet — add one from your phone" else "${indexers.count { it.enabled }} of ${indexers.size} enabled"

/** `Updating…`, `Never checked`, `Checked today` or `Last checked 3 Nov`. */
internal fun headerCheckLabel(checkedAtMillis: Long?, updating: Boolean, nowMillis: Long, zone: ZoneId): String = when {
    updating -> "Updating…"
    checkedAtMillis == null -> "Never checked"
    else -> {
        val checked = Instant.ofEpochMilli(checkedAtMillis).atZone(zone).toLocalDate()
        if (checked == LocalDate.ofInstant(Instant.ofEpochMilli(nowMillis), zone)) {
            "Checked today"
        } else {
            "Last checked " + checked.format(DateTimeFormatter.ofPattern("d MMM", Locale.US))
        }
    }
}

/** `news.example.com:563 · TLS · 20 connections · Backup` (+ `· Password needed`). */
internal fun providerSubtitle(provider: UsenetProvider): String = buildList {
    add("${provider.host}:${provider.port}")
    add(if (provider.tls) "TLS" else "Plain")
    add("${provider.maxConnections} connections")
    if (provider.isBackup) add("Backup")
    if (provider.passwordNeeded) add("Password needed")
    if (!provider.enabled) add("Disabled")
}.joinToString(" · ")

/** `api.nzbgeek.info · Backup only` (+ `· API key needed`). */
internal fun indexerSubtitle(indexer: NewznabIndexer): String = buildList {
    add(indexer.baseUrl.substringAfter("://") + indexer.apiPath)
    if (indexer.backupOnly) add("Backup only")
    if (indexer.apiKeyNeeded) add("API key needed")
    if (!indexer.enabled) add("Disabled")
}.joinToString(" · ")

const val USENET_SECTION_KEY = "section-usenet"
const val PROVIDERS_ROW_KEY = "usenet-providers"
const val INDEXERS_ROW_KEY = "usenet-indexers"
const val HEADERS_SEARCH_ROW_KEY = "headers-search"
const val HEADERS_DOWNLOAD_ROW_KEY = "headers-download"
const val HEADERS_UPDATE_ROW_KEY = "headers-update"
const val PERFORMANCE_ROW_KEY = "usenet-performance"
