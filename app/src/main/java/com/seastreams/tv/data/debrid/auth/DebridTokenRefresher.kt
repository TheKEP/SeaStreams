package com.seastreams.tv.data.debrid.auth

import com.seastreams.tv.data.debrid.DebridCredentials
import com.seastreams.tv.data.debrid.DebridSourcesRepository
import com.seastreams.tv.data.debrid.api.DebridAuthException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs a Debrid call with a usable credential (ARCHITECTURE §12). Device sign-ins that expire (Real-Debrid,
 * Debrid-Link) are refreshed [REFRESH_AHEAD_MILLIS] before `expiresAtMillis` and once after a `401`, then the call is
 * retried once. Refreshes are single-flight per provider: callers that find the token already renewed by someone else
 * use the new one. The service refusing the credential (or its refresh) marks the provider *Sign-in needed*; a call
 * it accepts clears that mark. Network errors pass through untouched.
 */
@Singleton
class DebridTokenRefresher @Inject constructor(
    private val repository: DebridSourcesRepository,
    private val refreshers: TokenRefreshers,
    private val clock: Clock,
) {
    private val locks = ConcurrentHashMap<Long, Mutex>()

    suspend fun <T> withCredentials(providerId: Long, call: suspend (DebridCredentials) -> T): T {
        var credentials = try {
            current(providerId)
        } catch (e: DebridAuthException) {
            repository.markSignInNeeded(providerId, true)
            throw e
        }
        val result = try {
            call(credentials)
        } catch (e: DebridAuthException) {
            if (credentials.refreshToken == null || refreshers.of(credentials.kind) == null) {
                repository.markSignInNeeded(providerId, true)
                throw e
            }
            credentials = refresh(providerId, credentials)
            try {
                call(credentials)
            } catch (again: DebridAuthException) {
                repository.markSignInNeeded(providerId, true)
                throw again
            }
        }
        repository.markSignInNeeded(providerId, false)
        return result
    }

    /** The stored credentials, renewed first when they expire within [REFRESH_AHEAD_MILLIS]. */
    private suspend fun current(providerId: Long): DebridCredentials {
        val credentials = repository.credentials(providerId)
        val expiresAt = credentials.expiresAtMillis ?: return credentials
        if (credentials.refreshToken == null || refreshers.of(credentials.kind) == null) return credentials
        return if (clock.millis() >= expiresAt - REFRESH_AHEAD_MILLIS) refresh(providerId, credentials) else credentials
    }

    private suspend fun refresh(providerId: Long, stale: DebridCredentials): DebridCredentials =
        locks.getOrPut(providerId) { Mutex() }.withLock {
            val latest = repository.credentials(providerId)
            // Someone else refreshed while this caller waited: use their token.
            if (latest.accessToken != stale.accessToken) return@withLock latest
            val refresher = refreshers.of(latest.kind) ?: throw DebridAuthException("Sign-in needed")
            val tokens = try {
                refresher.refresh(latest)
            } catch (e: DebridAuthException) {
                repository.markSignInNeeded(providerId, true)
                throw DebridAuthException("Sign-in needed")
            }
            repository.updateTokens(providerId, tokens, clock.millis())
            repository.credentials(providerId)
        }

    companion object {
        const val REFRESH_AHEAD_MILLIS = 5L * 60 * 1_000
    }
}
