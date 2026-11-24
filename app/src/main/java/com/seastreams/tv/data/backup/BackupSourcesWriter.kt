package com.seastreams.tv.data.backup

import com.seastreams.tv.core.database.dao.DebridProviderDao
import com.seastreams.tv.core.database.dao.NewznabIndexerDao
import com.seastreams.tv.core.database.dao.UsenetProviderDao
import com.seastreams.tv.core.database.entity.DebridProviderEntity
import com.seastreams.tv.core.database.entity.NewznabIndexerEntity
import com.seastreams.tv.core.database.entity.UsenetProviderEntity
import com.seastreams.tv.core.security.SecretCipher
import com.seastreams.tv.data.debrid.DebridServiceKind
import java.util.Locale
import javax.inject.Inject

/** What the source half of a restore wrote; [resealedDebrid] are the services whose credentials were replaced. */
internal data class RestoredSources(
    val providers: Int,
    val indexers: Int,
    val debridAccounts: Int,
    val credentials: Int,
    val resealedDebrid: Set<DebridServiceKind>,
)

/**
 * The app-wide source half of a restore's Room transaction (lock-free; `BackupRestorer` holds the locks). Each backed-up
 * row updates the TV's matching row (provider: server + port + username, indexer: address + API path, Debrid: service)
 * or is added; rows the backup doesn't mention are kept, after the restored ones. A secret in the backup is sealed with
 * [SecretCipher]; a missing one keeps the matching row's stored secret, and a new Debrid row without one is saved as
 * *Sign-in needed*.
 */
internal class BackupSourcesWriter @Inject constructor(
    private val providers: UsenetProviderDao,
    private val indexers: NewznabIndexerDao,
    private val debrid: DebridProviderDao,
    private val cipher: SecretCipher,
) {

    suspend fun write(usenet: UsenetBackup, debridBackup: DebridBackup): RestoredSources {
        var credentials = 0
        fun seal(secret: String?): String? = secret?.let { credentials++; cipher.encrypt(it) }

        val existingProviders = providers.getAll()
        val providerOrder = mutableListOf<Long>()
        usenet.providers.sortedBy { it.sortOrder }.forEach { p ->
            val match = existingProviders.firstOrNull { it.id !in providerOrder && it.matchKey() == p.matchKey() }
            val row = UsenetProviderEntity(
                id = match?.id ?: 0, name = p.name, host = p.host, port = p.port, tls = p.tls, username = p.username,
                passwordCipher = seal(p.password) ?: match?.passwordCipher,
                maxConnections = p.maxConnections, pipelineDepth = p.pipelineDepth, priority = p.priority,
                isBackup = p.isBackup, enabled = p.enabled, sortOrder = providerOrder.size,
            )
            providerOrder += if (match == null) providers.insert(row) else match.id.also { providers.update(row) }
        }
        providers.reorder(providerOrder + existingProviders.map { it.id }.filterNot { it in providerOrder })

        val existingIndexers = indexers.getAll()
        val indexerOrder = mutableListOf<Long>()
        usenet.indexers.sortedBy { it.sortOrder }.forEach { i ->
            val match = existingIndexers.firstOrNull { it.id !in indexerOrder && it.matchKey() == i.matchKey() }
            val row = NewznabIndexerEntity(
                id = match?.id ?: 0, name = i.name, baseUrl = i.baseUrl, apiPath = i.apiPath,
                apiKeyCipher = seal(i.apiKey) ?: match?.apiKeyCipher,
                priority = i.priority, backupOnly = i.backupOnly, enabled = i.enabled, sortOrder = indexerOrder.size,
            )
            indexerOrder += if (match == null) indexers.insert(row) else match.id.also { indexers.update(row) }
        }
        indexers.reorder(indexerOrder + existingIndexers.map { it.id }.filterNot { it in indexerOrder })

        val existingDebrid = debrid.getAll()
        val debridOrder = mutableListOf<Long>()
        val resealed = mutableSetOf<DebridServiceKind>()
        debridBackup.providers.sortedBy { it.sortOrder }.forEach { d ->
            val match = existingDebrid.firstOrNull { it.providerType == d.providerType }
            val row = when {
                d.credential != null -> {
                    DebridServiceKind.fromName(d.providerType)?.let(resealed::add)
                    DebridProviderEntity(
                        id = match?.id ?: 0, providerType = d.providerType, name = d.name, authMethod = d.authMethod,
                        credentialCipher = seal(d.credential).orEmpty(),
                        refreshTokenCipher = seal(d.refreshToken), clientIdCipher = seal(d.clientId), clientSecretCipher = seal(d.clientSecret),
                        expiresAtMillis = d.expiresAtMillis, signInNeeded = false, enabled = d.enabled, sortOrder = debridOrder.size,
                    )
                }
                // No credentials in the file: the TV's account keeps its own (and the sign-in method they came from).
                match != null -> match.copy(name = d.name, enabled = d.enabled, sortOrder = debridOrder.size)
                else -> DebridProviderEntity(
                    providerType = d.providerType, name = d.name, authMethod = d.authMethod, credentialCipher = "",
                    refreshTokenCipher = null, clientIdCipher = null, clientSecretCipher = null, expiresAtMillis = null,
                    signInNeeded = true, enabled = d.enabled, sortOrder = debridOrder.size,
                )
            }
            debridOrder += if (match == null) debrid.insert(row) else match.id.also { debrid.update(row) }
        }
        debrid.reorder(debridOrder + existingDebrid.map { it.id }.filterNot { it in debridOrder })

        return RestoredSources(usenet.providers.size, usenet.indexers.size, debridBackup.providers.size, credentials, resealed)
    }

    private fun UsenetProviderEntity.matchKey() = Triple(host.lowercase(Locale.ROOT), port, username)

    private fun NewznabIndexerEntity.matchKey() = baseUrl.lowercase(Locale.ROOT).trimEnd('/') to apiPath
}
