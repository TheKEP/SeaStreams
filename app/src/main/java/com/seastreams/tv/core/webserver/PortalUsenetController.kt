package com.seastreams.tv.core.webserver

import com.seastreams.tv.core.datastore.UsenetSettingsStore
import com.seastreams.tv.core.util.enumByNameOrNull
import com.seastreams.tv.data.UserFacingException
import com.seastreams.tv.data.usenet.IndexerDraft
import com.seastreams.tv.data.usenet.ProviderDraft
import com.seastreams.tv.data.usenet.UsenetSourcesRepository
import com.seastreams.tv.data.usenet.headers.HeaderPreset
import com.seastreams.tv.data.usenet.headers.HeaderUse
import com.seastreams.tv.data.usenet.headers.HeaderVersionUpdater
import com.seastreams.tv.data.usenet.presets.IndexerPresets
import com.seastreams.tv.data.usenet.presets.UsenetProviderPresets
import com.seastreams.tv.domain.usenet.UsenetProfile
import com.seastreams.tv.usenet.nntp.NntpConnectionTester
import com.seastreams.tv.usenet.nntp.ProviderTestResult
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** Web Portal actions for the app-wide Usenet Sources (PRD §8 item 4, §12). Returns confirmations or throws [UserFacingException]. */
@Singleton
class PortalUsenetController @Inject constructor(
    private val sources: UsenetSourcesRepository,
    private val store: UsenetSettingsStore,
    private val updater: HeaderVersionUpdater,
    private val tester: NntpConnectionTester,
) {

    suspend fun state(): UsenetPortalState {
        val headers = store.headers.first()
        return UsenetPortalState(
            providers = sources.providers.first().map {
                ProviderDto(
                    it.id, it.name, it.host, it.port, it.tls, it.username, it.hasPassword, it.passwordNeeded,
                    it.maxConnections, it.pipelineDepth, it.priority, it.isBackup, it.enabled,
                )
            },
            indexers = sources.indexers.first().map {
                IndexerDto(it.id, it.name, it.baseUrl, it.apiPath, it.hasApiKey, it.apiKeyNeeded, it.priority, it.backupOnly, it.enabled)
            },
            providerPresets = UsenetProviderPresets.ALL.map { ProviderPresetDto(it.name, it.host, it.port, it.tls, it.maxConnections) },
            indexerPresets = IndexerPresets.ALL.map { IndexerPresetDto(it.name, it.baseUrl, it.apiPath, it.needsApiKey) },
            headerPresets = HeaderPreset.entries.map { HeaderPresetDto(it.name, it.displayLabel(headers.versions)) },
            searchHeaders = headers.search.name,
            downloadHeaders = headers.download.name,
            headersCheckedAtMillis = headers.checkedAtMillis,
            performance = store.performance.first(),
        )
    }

    suspend fun saveProvider(request: ProviderRequest): String {
        sources.saveProvider(request.id, request.toDraft())
        return if (request.id == null) "Provider added" else "Provider saved"
    }

    /**
     * The portal's provider `Test`: tests the form as typed (a blank password on a saved provider uses the stored
     * one). Success is a confirmation; a bad login, connection limit or unreachable server is an error toast.
     */
    suspend fun testProvider(request: ProviderRequest): String {
        val endpoint = sources.endpointForDraft(request.id, request.toDraft())
        val dialTimeoutMs = store.performance.first().dialTimeoutSeconds * 1_000
        val result = tester.test(endpoint, dialTimeoutMs)
        if (result !is ProviderTestResult.Success) throw UserFacingException(result.message)
        return result.message
    }

    private fun ProviderRequest.toDraft() =
        ProviderDraft(name, host, port, tls, username, password, maxConnections, pipelineDepth, priority, isBackup, enabled)

    suspend fun removeProvider(request: SourceIdRequest): String {
        sources.removeProvider(request.id)
        return "Provider removed"
    }

    suspend fun setProviderEnabled(request: SourceEnabledRequest): String {
        sources.setProviderEnabled(request.id, request.enabled)
        return if (request.enabled) "Provider enabled" else "Provider disabled"
    }

    suspend fun moveProvider(request: SourceMoveRequest): String {
        sources.moveProvider(request.id, request.offset)
        return "Order updated"
    }

    suspend fun saveIndexer(request: IndexerRequest): String {
        val draft = with(request) { IndexerDraft(name, baseUrl, apiPath, apiKey, priority, backupOnly, enabled) }
        sources.saveIndexer(request.id, draft)
        return if (request.id == null) "Indexer added" else "Indexer saved"
    }

    suspend fun removeIndexer(request: SourceIdRequest): String {
        sources.removeIndexer(request.id)
        return "Indexer removed"
    }

    suspend fun setIndexerEnabled(request: SourceEnabledRequest): String {
        sources.setIndexerEnabled(request.id, request.enabled)
        return if (request.enabled) "Indexer enabled" else "Indexer disabled"
    }

    suspend fun moveIndexer(request: SourceMoveRequest): String {
        sources.moveIndexer(request.id, request.offset)
        return "Order updated"
    }

    suspend fun setHeaders(request: HeadersRequest): String {
        store.setHeaderPreset(HeaderUse.SEARCH, preset(request.search))
        store.setHeaderPreset(HeaderUse.DOWNLOAD, preset(request.download))
        return "Request headers saved"
    }

    suspend fun updateHeaders(): String =
        if (updater.updateNow()) "Header versions updated" else "Couldn't reach GitHub, so the current versions are kept"

    suspend fun setPerformance(request: PerformanceRequest): String {
        val values = request.values
        val profile = request.profile?.let { enumByNameOrNull<UsenetProfile>(it) ?: throw UserFacingException("Unknown profile") }
        when {
            values != null -> store.updatePerformance { it.edited { values } }
            profile != null -> store.updatePerformance { it.withProfile(profile) }
            else -> throw UserFacingException("Nothing to save")
        }
        return "Usenet performance saved"
    }

    private fun preset(name: String): HeaderPreset = enumByNameOrNull<HeaderPreset>(name) ?: throw UserFacingException("Unknown header preset")
}
