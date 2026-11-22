package com.seastreams.tv.core.webserver

import com.seastreams.tv.domain.usenet.UsenetPerformance
import kotlinx.serialization.Serializable

// Usenet Sources (PRD §12). Secrets travel only browser → TV: responses carry `hasPassword` /
// `hasApiKey` flags, never the values. A blank `password` / `apiKey` on edit keeps the stored one.

/** [id] = `null` adds a provider. */
@Serializable
data class ProviderRequest(
    val id: Long? = null,
    val name: String,
    val host: String,
    val port: Int = 563,
    val tls: Boolean = true,
    val username: String = "",
    val password: String? = null,
    val maxConnections: Int = 20,
    val pipelineDepth: Int = 2,
    val priority: Int = 0,
    val isBackup: Boolean = false,
    val enabled: Boolean = true,
)

/** [id] = `null` adds an indexer. */
@Serializable
data class IndexerRequest(
    val id: Long? = null,
    val name: String,
    val baseUrl: String,
    val apiPath: String = "/api",
    val apiKey: String? = null,
    val priority: Int = 0,
    val backupOnly: Boolean = false,
    val enabled: Boolean = true,
)

@Serializable
data class SourceIdRequest(val id: Long)

@Serializable
data class SourceEnabledRequest(val id: Long, val enabled: Boolean)

@Serializable
data class SourceMoveRequest(val id: Long, val offset: Int)

/** Preset names, e.g. `PROWLARR` / `SABNZBD`. */
@Serializable
data class HeadersRequest(val search: String, val download: String)

/** Either a profile template ([profile] = `CONSERVATIVE`/`BALANCED`/`HIGH`/`CUSTOM`) or edited [values] (→ Custom). */
@Serializable
data class PerformanceRequest(val profile: String? = null, val values: UsenetPerformance? = null)

@Serializable
data class ProviderDto(
    val id: Long,
    val name: String,
    val host: String,
    val port: Int,
    val tls: Boolean,
    val username: String,
    val hasPassword: Boolean,
    val passwordNeeded: Boolean,
    val maxConnections: Int,
    val pipelineDepth: Int,
    val priority: Int,
    val isBackup: Boolean,
    val enabled: Boolean,
)

@Serializable
data class IndexerDto(
    val id: Long,
    val name: String,
    val baseUrl: String,
    val apiPath: String,
    val hasApiKey: Boolean,
    val apiKeyNeeded: Boolean,
    val priority: Int,
    val backupOnly: Boolean,
    val enabled: Boolean,
)

@Serializable
data class ProviderPresetDto(val name: String, val host: String, val port: Int, val tls: Boolean, val maxConnections: Int)

@Serializable
data class IndexerPresetDto(val name: String, val baseUrl: String, val apiPath: String, val needsApiKey: Boolean)

/** [label] includes the current version, e.g. `Prowlarr 2.7.0.5723`. */
@Serializable
data class HeaderPresetDto(val name: String, val label: String)

@Serializable
data class UsenetPortalState(
    val providers: List<ProviderDto>,
    val indexers: List<IndexerDto>,
    val providerPresets: List<ProviderPresetDto>,
    val indexerPresets: List<IndexerPresetDto>,
    val headerPresets: List<HeaderPresetDto>,
    val searchHeaders: String,
    val downloadHeaders: String,
    val headersCheckedAtMillis: Long?,
    val performance: UsenetPerformance,
)
