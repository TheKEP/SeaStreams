package com.seastreams.tv.data.debrid

import com.seastreams.tv.core.database.dao.DebridProviderDao
import com.seastreams.tv.core.database.entity.DebridProviderEntity
import com.seastreams.tv.core.security.SecretCipher
import com.seastreams.tv.core.util.IoDispatcher
import com.seastreams.tv.data.UserFacingException
import com.seastreams.tv.data.debrid.api.DebridAuthException
import com.seastreams.tv.data.debrid.auth.DeviceTokens
import com.seastreams.tv.data.usenet.moveId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.EnumMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-wide Debrid accounts (PRD §14), shared by the TV Sources tab and the Web Portal. Every key and token passes
 * through [SecretCipher] on [io]; display models carry only a [DebridCredentialState]. One account per service.
 *
 * Each service has a credential *generation*, bumped whenever its account is replaced, removed or cleared. A device
 * sign-in records the generation when it starts and is discarded if it changed by the time the code completes
 * ([saveDeviceSignIn]), so a late code can't bring back a removed or cleared account.
 */
@Singleton
class DebridSourcesRepository @Inject constructor(
    private val dao: DebridProviderDao,
    private val cipher: SecretCipher,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val lock = Mutex()
    private val generations = EnumMap<DebridServiceKind, Long>(DebridServiceKind::class.java)

    val providers: Flow<List<DebridProvider>> = dao.observeAll()
        .map { rows -> rows.mapNotNull(::toProvider) }
        .flowOn(io)

    /** The current credential generation of [kind]. */
    fun generation(kind: DebridServiceKind): Long = synchronized(generations) { generations[kind] ?: 0L }

    private fun bump(kind: DebridServiceKind) {
        synchronized(generations) { generations[kind] = (generations[kind] ?: 0L) + 1 }
    }

    /**
     * Adds ([id] = `null`) or edits a pasted-key account; returns its id. A new account needs a key; on an edit a
     * blank key keeps the stored credential (and only the name changes).
     */
    suspend fun saveKey(id: Long?, kind: DebridServiceKind, apiKey: String?, name: String?): Long = lock.withLock {
        val label = DebridSourceValidation.name(kind, name)
        val key = apiKey?.takeIf { it.isNotBlank() }?.let { DebridSourceValidation.key(kind, it) }
        val existing = if (id == null) {
            if (dao.getByType(kind.name) != null) throw UserFacingException("${kind.displayName} is already added. Edit it instead")
            null
        } else {
            dao.get(id)?.takeIf { it.providerType == kind.name } ?: throw UserFacingException("Provider not found")
        }
        if (key == null) {
            if (existing == null) throw UserFacingException("Enter your ${kind.displayName} ${kind.keyLabel}")
            dao.update(existing.copy(name = label))
            return@withLock existing.id
        }
        val sealed = withContext(io) { cipher.encrypt(key) }
        bump(kind)
        val row = DebridProviderEntity(
            id = existing?.id ?: 0,
            providerType = kind.name,
            name = label,
            authMethod = DebridAuthMethod.API_KEY.name,
            credentialCipher = sealed,
            refreshTokenCipher = null,
            clientIdCipher = null,
            clientSecretCipher = null,
            expiresAtMillis = null,
            signInNeeded = false,
            enabled = existing?.enabled ?: true,
            sortOrder = existing?.sortOrder ?: dao.nextSortOrder(),
        )
        if (existing == null) dao.insert(row) else row.id.also { dao.update(row) }
    }

    /**
     * Stores a finished device sign-in for [kind] (adding the account, or replacing its credential while keeping its
     * name, position and on/off state). Returns `null` and stores nothing when [generation] is stale: the account was
     * removed, replaced or cleared after the sign-in started.
     */
    suspend fun saveDeviceSignIn(kind: DebridServiceKind, tokens: DeviceTokens, generation: Long, nowMillis: Long): Long? =
        lock.withLock {
            if (generation(kind) != generation) return@withLock null
            val sealed = withContext(io) { seal(tokens) }
            bump(kind)
            val existing = dao.getByType(kind.name)
            val row = DebridProviderEntity(
                id = existing?.id ?: 0,
                providerType = kind.name,
                name = existing?.name ?: kind.displayName,
                authMethod = DebridAuthMethod.DEVICE.name,
                credentialCipher = sealed.access,
                refreshTokenCipher = sealed.refresh,
                clientIdCipher = sealed.clientId,
                clientSecretCipher = sealed.clientSecret,
                expiresAtMillis = tokens.expiresInSeconds?.let { nowMillis + it * MILLIS_PER_SECOND },
                signInNeeded = false,
                enabled = existing?.enabled ?: true,
                sortOrder = existing?.sortOrder ?: dao.nextSortOrder(),
            )
            if (existing == null) dao.insert(row) else row.id.also { dao.update(row) }
        }

    /**
     * A refreshed device sign-in: new access (and refresh) token, the credential usable again. Returns `false` and
     * stores nothing when [generation] is stale: the account was replaced (say, by a pasted key), removed or cleared
     * while the refresh was in flight, so the refreshed token must not overwrite the newer credential.
     */
    suspend fun updateTokens(id: Long, tokens: DeviceTokens, generation: Long, nowMillis: Long): Boolean = lock.withLock {
        val row = dao.get(id) ?: throw UserFacingException("Provider not found")
        val kind = DebridServiceKind.fromName(row.providerType) ?: return@withLock false
        if (generation(kind) != generation) return@withLock false
        val access = withContext(io) { cipher.encrypt(tokens.accessToken) }
        val refresh = tokens.refreshToken?.let { withContext(io) { cipher.encrypt(it) } } ?: row.refreshTokenCipher
        dao.updateTokens(id, access, refresh, tokens.expiresInSeconds?.let { nowMillis + it * MILLIS_PER_SECOND })
        true
    }

    /** Flags (or clears) *Sign-in needed* after the service refused (or accepted) the stored credential. */
    suspend fun markSignInNeeded(id: Long, needed: Boolean) {
        val row = dao.get(id) ?: return
        if (row.signInNeeded != needed) dao.setSignInNeeded(id, needed)
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) {
        dao.get(id) ?: throw UserFacingException("Provider not found")
        dao.setEnabled(id, enabled)
    }

    suspend fun move(id: Long, offset: Int) = lock.withLock {
        val order = dao.getAll().map { it.id }
        if (id !in order) throw UserFacingException("Provider not found")
        dao.reorder(moveId(order, id, offset))
    }

    suspend fun remove(id: Long) = lock.withLock {
        val row = dao.get(id) ?: throw UserFacingException("Provider not found")
        DebridServiceKind.fromName(row.providerType)?.let(::bump)
        dao.delete(id)
        dao.reorder(dao.getAll().map { it.id })
    }

    /** `Clear Debrid settings`: every account and its stored keys/tokens; pending sign-ins can no longer land. */
    suspend fun deleteAll() = lock.withLock {
        DebridServiceKind.entries.forEach(::bump)
        dao.deleteAll()
    }

    /** One account's decrypted credentials; an undecryptable one is a [DebridAuthException] (*Sign-in needed*). */
    suspend fun credentials(id: Long): DebridCredentials {
        val row = dao.get(id) ?: throw UserFacingException("Provider not found")
        return withContext(io) { decrypt(row) } ?: throw DebridAuthException("Sign-in needed")
    }

    /** Enabled accounts in list order with usable credentials (undecryptable or revoked ones skipped). */
    suspend fun enabledCredentials(): List<DebridCredentials> = withContext(io) {
        dao.getAll().filter { it.enabled && !it.signInNeeded }.mapNotNull(::decrypt)
    }

    private fun decrypt(row: DebridProviderEntity): DebridCredentials? {
        val kind = DebridServiceKind.fromName(row.providerType) ?: return null
        val access = cipher.decrypt(row.credentialCipher) ?: return null
        return DebridCredentials(
            providerId = row.id,
            kind = kind,
            accessToken = access,
            refreshToken = row.refreshTokenCipher?.let(cipher::decrypt),
            clientId = row.clientIdCipher?.let(cipher::decrypt),
            clientSecret = row.clientSecretCipher?.let(cipher::decrypt),
            expiresAtMillis = row.expiresAtMillis,
        )
    }

    private class Sealed(val access: String, val refresh: String?, val clientId: String?, val clientSecret: String?)

    private fun seal(tokens: DeviceTokens) = Sealed(
        access = cipher.encrypt(tokens.accessToken),
        refresh = tokens.refreshToken?.let(cipher::encrypt),
        clientId = tokens.clientId?.let(cipher::encrypt),
        clientSecret = tokens.clientSecret?.let(cipher::encrypt),
    )

    private fun toProvider(row: DebridProviderEntity): DebridProvider? {
        val kind = DebridServiceKind.fromName(row.providerType) ?: return null
        val method = if (row.authMethod == DebridAuthMethod.DEVICE.name) DebridAuthMethod.DEVICE else DebridAuthMethod.API_KEY
        val state = when {
            row.signInNeeded || cipher.decrypt(row.credentialCipher) == null -> DebridCredentialState.SIGN_IN_NEEDED
            method == DebridAuthMethod.DEVICE -> DebridCredentialState.SIGNED_IN
            else -> DebridCredentialState.API_KEY
        }
        return DebridProvider(row.id, kind, row.name, method, state, row.enabled)
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
    }
}
