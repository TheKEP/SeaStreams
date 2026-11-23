package com.seastreams.tv.data.usenet

import com.seastreams.tv.core.database.dao.NewznabIndexerDao
import com.seastreams.tv.core.database.dao.UsenetProviderDao
import com.seastreams.tv.core.database.entity.NewznabIndexerEntity
import com.seastreams.tv.core.database.entity.UsenetProviderEntity
import com.seastreams.tv.core.security.SecretCipher
import com.seastreams.tv.core.util.IoDispatcher
import com.seastreams.tv.data.UserFacingException
import com.seastreams.tv.data.usenet.newznab.NewznabEndpoint
import com.seastreams.tv.usenet.nntp.NntpEndpoint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-wide Usenet providers and Newznab indexers (PRD §12), shared by the TV Sources tab and the
 * Web Portal. Secrets pass through [SecretCipher] on [io] and are never returned in the display
 * models. Sort orders stay dense; list edits are serialised so concurrent TV/portal moves can't interleave.
 */
@Singleton
class UsenetSourcesRepository @Inject constructor(
    private val providerDao: UsenetProviderDao,
    private val indexerDao: NewznabIndexerDao,
    private val cipher: SecretCipher,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val orderLock = Mutex()

    val providers: Flow<List<UsenetProvider>> = providerDao.observeAll()
        .map { rows -> rows.map(::toProvider) }
        .flowOn(io)

    val indexers: Flow<List<NewznabIndexer>> = indexerDao.observeAll()
        .map { rows -> rows.map(::toIndexer) }
        .flowOn(io)

    /** Adds ([id] = `null`) or replaces a provider; returns its id. */
    suspend fun saveProvider(id: Long?, draft: ProviderDraft): Long = orderLock.withLock {
        val valid = UsenetSourceValidation.provider(draft)
        val existing = id?.let { providerDao.get(it) ?: throw UserFacingException("Provider not found") }
        val password = withContext(io) { sealOrKeep(valid.password, existing?.passwordCipher) }
        val entity = UsenetProviderEntity(
            id = existing?.id ?: 0,
            name = valid.name,
            host = valid.host,
            port = valid.port,
            tls = valid.tls,
            username = valid.username,
            passwordCipher = password,
            maxConnections = valid.maxConnections,
            pipelineDepth = valid.pipelineDepth,
            priority = valid.priority,
            isBackup = valid.isBackup,
            enabled = valid.enabled,
            sortOrder = existing?.sortOrder ?: providerDao.nextSortOrder(),
        )
        if (existing == null) providerDao.insert(entity) else entity.id.also { providerDao.update(entity) }
    }

    suspend fun setProviderEnabled(id: Long, enabled: Boolean) {
        providerDao.get(id) ?: throw UserFacingException("Provider not found")
        providerDao.setEnabled(id, enabled)
    }

    suspend fun moveProvider(id: Long, offset: Int) = orderLock.withLock {
        val order = providerDao.getAll().map { it.id }
        if (id !in order) throw UserFacingException("Provider not found")
        providerDao.reorder(moveId(order, id, offset))
    }

    suspend fun removeProvider(id: Long) = orderLock.withLock {
        providerDao.get(id) ?: throw UserFacingException("Provider not found")
        providerDao.delete(id)
        providerDao.reorder(providerDao.getAll().map { it.id })
    }

    /** Adds ([id] = `null`) or replaces an indexer; returns its id. */
    suspend fun saveIndexer(id: Long?, draft: IndexerDraft): Long = orderLock.withLock {
        val valid = UsenetSourceValidation.indexer(draft)
        val existing = id?.let { indexerDao.get(it) ?: throw UserFacingException("Indexer not found") }
        val apiKey = withContext(io) { sealOrKeep(valid.apiKey, existing?.apiKeyCipher) }
        val entity = NewznabIndexerEntity(
            id = existing?.id ?: 0,
            name = valid.name,
            baseUrl = valid.baseUrl,
            apiPath = valid.apiPath,
            apiKeyCipher = apiKey,
            priority = valid.priority,
            backupOnly = valid.backupOnly,
            enabled = valid.enabled,
            sortOrder = existing?.sortOrder ?: indexerDao.nextSortOrder(),
        )
        if (existing == null) indexerDao.insert(entity) else entity.id.also { indexerDao.update(entity) }
    }

    suspend fun setIndexerEnabled(id: Long, enabled: Boolean) {
        indexerDao.get(id) ?: throw UserFacingException("Indexer not found")
        indexerDao.setEnabled(id, enabled)
    }

    suspend fun moveIndexer(id: Long, offset: Int) = orderLock.withLock {
        val order = indexerDao.getAll().map { it.id }
        if (id !in order) throw UserFacingException("Indexer not found")
        indexerDao.reorder(moveId(order, id, offset))
    }

    suspend fun removeIndexer(id: Long) = orderLock.withLock {
        indexerDao.get(id) ?: throw UserFacingException("Indexer not found")
        indexerDao.delete(id)
        indexerDao.reorder(indexerDao.getAll().map { it.id })
    }

    /** Enabled providers in sort order with decrypted passwords, for the NNTP engine. Undecryptable ones are skipped. */
    suspend fun enabledEndpoints(): List<NntpEndpoint> = withContext(io) {
        providerDao.getAll().filter { it.enabled }.mapNotNull { row ->
            val password = row.passwordCipher?.let(cipher::decrypt)
            when {
                row.passwordCipher != null && password == null -> null
                row.username.isNotEmpty() && password.isNullOrEmpty() -> null
                else -> endpointOf(row.id, row.toDraft(), password)
            }
        }
    }

    /** One saved provider for its `Test` button. A password that can't be decrypted is reported as *Password needed*. */
    suspend fun endpoint(id: Long): NntpEndpoint {
        val row = providerDao.get(id) ?: throw UserFacingException("Provider not found")
        return withContext(io) { endpointOf(row.id, row.toDraft(), storedPassword(row)) }
    }

    /**
     * A portal form being tested before it is saved: validated like a save; a blank password on an existing
     * provider ([id]) uses the stored one.
     */
    suspend fun endpointForDraft(id: Long?, draft: ProviderDraft): NntpEndpoint {
        val valid = UsenetSourceValidation.provider(draft)
        val typed = valid.password?.takeIf { it.isNotEmpty() }
        val password = typed ?: id?.let { providerDao.get(it) ?: throw UserFacingException("Provider not found") }
            ?.let { withContext(io) { storedPassword(it) } }
        return endpointOf(id ?: 0, valid, password)
    }

    /** Enabled indexers in sort order with decrypted API keys, for searches. Undecryptable keys are skipped. */
    suspend fun enabledIndexers(): List<NewznabEndpoint> = withContext(io) {
        indexerDao.getAll().filter { it.enabled }.mapNotNull { row ->
            val key = row.apiKeyCipher?.let(cipher::decrypt)
            if (row.apiKeyCipher != null && key == null) null else indexerOf(row.id, row.toDraft(), key)
        }
    }

    /** One saved indexer for its `Test` button; an undecryptable key is reported as *API key needed*. */
    suspend fun indexerEndpoint(id: Long): NewznabEndpoint {
        val row = indexerDao.get(id) ?: throw UserFacingException("Indexer not found")
        return withContext(io) { indexerOf(row.id, row.toDraft(), storedApiKey(row)) }
    }

    /** A portal indexer form tested before saving; a blank key on a saved indexer ([id]) uses the stored one. */
    suspend fun indexerEndpointForDraft(id: Long?, draft: IndexerDraft): NewznabEndpoint {
        val valid = UsenetSourceValidation.indexer(draft)
        val typed = valid.apiKey?.takeIf { it.isNotEmpty() }
        val key = typed ?: id?.let { indexerDao.get(it) ?: throw UserFacingException("Indexer not found") }
            ?.let { withContext(io) { storedApiKey(it) } }
        return indexerOf(id ?: 0, valid, key)
    }

    private fun storedApiKey(row: NewznabIndexerEntity): String? = row.apiKeyCipher?.let {
        cipher.decrypt(it) ?: throw UserFacingException("API key needed: enter it again in the Web Portal")
    }

    private fun NewznabIndexerEntity.toDraft() = IndexerDraft(name, baseUrl, apiPath, null, priority, backupOnly, enabled)

    private fun indexerOf(id: Long, draft: IndexerDraft, apiKey: String?) = NewznabEndpoint(
        id = id,
        name = draft.name,
        apiUrl = draft.baseUrl.trimEnd('/') + draft.apiPath,
        apiKey = apiKey.orEmpty(),
        priority = draft.priority,
        backupOnly = draft.backupOnly,
    )

    private fun storedPassword(row: UsenetProviderEntity): String? = row.passwordCipher?.let {
        cipher.decrypt(it) ?: throw UserFacingException("Password needed: enter it again in the Web Portal")
    }

    private fun UsenetProviderEntity.toDraft() = ProviderDraft(
        name, host, port, tls, username, null, maxConnections, pipelineDepth, priority, isBackup, enabled,
    )

    private fun endpointOf(id: Long, draft: ProviderDraft, password: String?): NntpEndpoint {
        if (draft.username.isNotEmpty() && password.isNullOrEmpty()) throw UserFacingException("Enter the provider's password")
        return NntpEndpoint(
            id = id,
            name = draft.name,
            host = draft.host,
            port = draft.port,
            tls = draft.tls,
            username = draft.username,
            password = password.orEmpty(),
            maxConnections = draft.maxConnections,
            pipelineDepth = draft.pipelineDepth,
            priority = draft.priority,
            isBackup = draft.isBackup,
        )
    }

    /** A new non-blank secret is encrypted; a blank or absent one keeps [stored]. */
    private fun sealOrKeep(secret: String?, stored: String?): String? =
        if (secret.isNullOrEmpty()) stored else cipher.encrypt(secret)

    private fun toProvider(row: UsenetProviderEntity) = UsenetProvider(
        id = row.id,
        name = row.name,
        host = row.host,
        port = row.port,
        tls = row.tls,
        username = row.username,
        hasPassword = row.passwordCipher != null,
        passwordNeeded = row.passwordCipher?.let { cipher.decrypt(it) == null } ?: false,
        maxConnections = row.maxConnections,
        pipelineDepth = row.pipelineDepth,
        priority = row.priority,
        isBackup = row.isBackup,
        enabled = row.enabled,
    )

    private fun toIndexer(row: NewznabIndexerEntity) = NewznabIndexer(
        id = row.id,
        name = row.name,
        baseUrl = row.baseUrl,
        apiPath = row.apiPath,
        hasApiKey = row.apiKeyCipher != null,
        apiKeyNeeded = row.apiKeyCipher?.let { cipher.decrypt(it) == null } ?: false,
        priority = row.priority,
        backupOnly = row.backupOnly,
        enabled = row.enabled,
    )
}
