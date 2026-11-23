package com.seastreams.tv.core.webserver

import com.seastreams.tv.core.datastore.DebridSettingsStore
import com.seastreams.tv.core.datastore.UsenetSettingsStore
import com.seastreams.tv.data.UserFacingException
import com.seastreams.tv.data.debrid.DebridClientIds
import com.seastreams.tv.data.debrid.DebridConnectionTester
import com.seastreams.tv.data.debrid.DebridServiceKind
import com.seastreams.tv.data.debrid.DebridSourcesRepository
import com.seastreams.tv.data.debrid.DebridTestResult
import com.seastreams.tv.data.debrid.auth.DeviceAuthSession
import com.seastreams.tv.data.debrid.auth.DeviceAuthSessions
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Web Portal actions for the app-wide Debrid accounts (PRD §14) and the two source toggles. Keys and tokens are
 * write-only: no response ever carries one. Returns confirmations or throws [UserFacingException].
 */
@Singleton
class PortalDebridController @Inject constructor(
    private val repository: DebridSourcesRepository,
    private val store: DebridSettingsStore,
    private val usenetStore: UsenetSettingsStore,
    private val tester: DebridConnectionTester,
    private val sessions: DeviceAuthSessions,
    private val clientIds: DebridClientIds,
    private val clock: Clock,
) {

    suspend fun state(): DebridPortalState = DebridPortalState(
        enabled = store.enabled.first(),
        services = DebridServiceKind.entries.map { kind ->
            DebridServiceDto(
                kind = kind.name,
                name = kind.displayName,
                keyLabel = kind.keyLabel,
                keyPageUrl = kind.keyPageUrl,
                deviceSignIn = kind.deviceSignIn?.label?.takeIf { kind.deviceSignInAvailable(clientIds) },
            )
        },
        providers = repository.providers.first().map {
            DebridProviderDto(
                id = it.id,
                kind = it.kind.name,
                name = it.name,
                enabled = it.enabled,
                authMethod = it.authMethod.name,
                hasCredential = true,
                credentialState = it.credentialState.name,
                credentialLabel = it.credentialState.label,
            )
        },
    )

    suspend fun saveProvider(request: DebridProviderRequest): String {
        val kind = kind(request.kind)
        repository.saveKey(request.id, kind, request.apiKey, request.name)
        return if (request.id == null) "${kind.displayName} added" else "${kind.displayName} saved"
    }

    suspend fun removeProvider(request: SourceIdRequest): String {
        repository.remove(request.id)
        return "Provider removed"
    }

    suspend fun setProviderEnabled(request: SourceEnabledRequest): String {
        repository.setEnabled(request.id, request.enabled)
        return if (request.enabled) "Provider enabled" else "Provider disabled"
    }

    suspend fun moveProvider(request: SourceMoveRequest): String {
        repository.move(request.id, request.offset)
        return "Order updated"
    }

    /** A typed key is tested as is; otherwise the saved account. Anything but *connected* is an error toast. */
    suspend fun test(request: DebridTestRequest): String {
        val typed = request.apiKey?.takeIf { it.isNotBlank() }
        val (kind, result) = when {
            typed != null -> kind(request.kind.orEmpty()).let { it to tester.testKey(it, typed) }
            request.id != null -> tester.test(request.id)
            else -> throw UserFacingException("Enter a key to test")
        }
        val message = kind?.let(result::message) ?: "Sign-in needed: connect it again"
        if (result !is DebridTestResult.Success) throw UserFacingException(message)
        return message
    }

    suspend fun startAuth(request: DebridAuthStartRequest): DebridAuthSessionDto = sessions.start(kind(request.kind)).toDto()

    fun authStatus(session: String): DebridAuthSessionDto? = sessions.status(session)?.toDto()

    fun cancelAuth(request: DebridAuthCancelRequest): String {
        sessions.cancel(request.session)
        return "Sign-in cancelled"
    }

    suspend fun setToggles(request: SourceTogglesRequest): String {
        if (request.usenet == null && request.debrid == null) throw UserFacingException("Nothing to save")
        request.usenet?.let { usenetStore.setEnabled(it) }
        request.debrid?.let { store.setEnabled(it) }
        return "Sources updated"
    }

    private fun DeviceAuthSession.toDto() = DebridAuthSessionDto(
        session = id,
        kind = kind.name,
        userCode = userCode,
        verificationUrl = verificationUrl,
        expiresInSeconds = ((expiresAtMillis - clock.millis()) / MILLIS_PER_SECOND).coerceAtLeast(0),
        status = status.name.lowercase(Locale.ROOT),
    )

    private fun kind(name: String): DebridServiceKind =
        DebridServiceKind.fromName(name) ?: throw UserFacingException("Unknown Debrid service")

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
    }
}
