package com.seastreams.tv.data.debrid.auth

import com.seastreams.tv.data.debrid.DebridCredentialState
import com.seastreams.tv.data.debrid.DebridCredentials
import com.seastreams.tv.data.debrid.DebridServiceKind
import com.seastreams.tv.data.debrid.DebridSourcesRepository
import com.seastreams.tv.data.debrid.api.DebridAuthException
import com.seastreams.tv.testutil.FakeDebridProviderDao
import com.seastreams.tv.testutil.FakeSecretCipher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

/** Real-Debrid-style token refresh (ARCHITECTURE §12): pre-expiry, after a 401, single-flight, failure → Sign-in needed. */
class DebridTokenRefresherTest {

    private val now = 1_800_000_000_000L
    private val clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)
    private val dao = FakeDebridProviderDao()
    private val repository = DebridSourcesRepository(dao, FakeSecretCipher(), Dispatchers.Unconfined)

    /** Hands out `access-N` / `refresh-N`; [gate] (when set) holds each refresh until completed; [fail] refuses. */
    private inner class CountingRefresh : TokenRefresh {
        val calls = AtomicInteger()
        var gate: CompletableDeferred<Unit>? = null
        var fail = false
        val seen = mutableListOf<String?>()

        override suspend fun refresh(credentials: DebridCredentials): DeviceTokens {
            seen += credentials.refreshToken
            gate?.await()
            if (fail) throw DebridAuthException()
            val n = calls.incrementAndGet()
            return DeviceTokens("access-$n", "refresh-$n", expiresInSeconds = 3_600)
        }
    }

    private val refresh = CountingRefresh()
    private val refresher = DebridTokenRefresher(repository, TokenRefreshers { if (it == DebridServiceKind.REAL_DEBRID) refresh else null }, clock)

    private suspend fun signIn(expiresInSeconds: Long): Long {
        val generation = repository.generation(DebridServiceKind.REAL_DEBRID)
        val tokens = DeviceTokens("access-0", "refresh-0", "cid", "secret", expiresInSeconds)
        return checkNotNull(repository.saveDeviceSignIn(DebridServiceKind.REAL_DEBRID, tokens, generation, now))
    }

    @Test
    fun `a token far from expiry is used as is`() = runTest {
        val id = signIn(expiresInSeconds = 3_600)
        assertEquals("access-0", refresher.withCredentials(id) { it.accessToken })
        assertEquals(0, refresh.calls.get())
    }

    @Test
    fun `a token within five minutes of expiry is refreshed first and the new one stored`() = runTest {
        val id = signIn(expiresInSeconds = 4 * 60)
        assertEquals("access-1", refresher.withCredentials(id) { it.accessToken })
        assertEquals(listOf<String?>("refresh-0"), refresh.seen)
        val stored = repository.credentials(id)
        assertEquals("refresh-1" to now + 3_600_000, stored.refreshToken to stored.expiresAtMillis)
    }

    @Test
    fun `a 401 refreshes once and retries the call`() = runTest {
        val id = signIn(expiresInSeconds = 3_600)
        val used = mutableListOf<String>()
        val result = refresher.withCredentials(id) {
            used += it.accessToken
            if (it.accessToken == "access-0") throw DebridAuthException()
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(listOf("access-0", "access-1"), used)
        assertEquals(1, refresh.calls.get())
    }

    @Test
    fun `concurrent callers share one refresh`() = runTest {
        val id = signIn(expiresInSeconds = 60)
        refresh.gate = CompletableDeferred()
        val calls = List(5) { async { refresher.withCredentials(id) { it.accessToken } } }
        testScheduler.runCurrent()
        checkNotNull(refresh.gate).complete(Unit)
        assertEquals(List(5) { "access-1" }, calls.awaitAll())
        assertEquals(1, refresh.calls.get())
    }

    @Test
    fun `a refresh that lands after a key was pasted keeps the pasted key`() = runTest {
        val id = signIn(expiresInSeconds = 60)
        refresh.gate = CompletableDeferred()
        val call = async { refresher.withCredentials(id) { it.accessToken } }
        testScheduler.runCurrent()
        repository.saveKey(id, DebridServiceKind.REAL_DEBRID, "rd-pasted-key-0123456789", null)
        checkNotNull(refresh.gate).complete(Unit)

        assertEquals("rd-pasted-key-0123456789", call.await())
        val stored = repository.credentials(id)
        assertEquals("rd-pasted-key-0123456789" to null, stored.accessToken to stored.refreshToken)
        assertEquals(DebridCredentialState.API_KEY, repository.providers.first().single().credentialState)
    }

    @Test
    fun `a refused refresh marks the provider Sign-in needed`() = runTest {
        val id = signIn(expiresInSeconds = 60)
        refresh.fail = true
        assertThrows<DebridAuthException> { refresher.withCredentials(id) { it.accessToken } }
        assertEquals(DebridCredentialState.SIGN_IN_NEEDED, repository.providers.first().single().credentialState)
    }

    @Test
    fun `a key that can't refresh and is rejected marks Sign-in needed, and success clears it`() = runTest {
        val id = repository.saveKey(null, DebridServiceKind.TORBOX, "tb-key-0123456789", null)
        assertThrows<DebridAuthException> { refresher.withCredentials(id) { throw DebridAuthException() } }
        assertEquals(DebridCredentialState.SIGN_IN_NEEDED, repository.providers.first().single().credentialState)
        refresher.withCredentials(id) { it.accessToken }
        assertEquals(DebridCredentialState.API_KEY, repository.providers.first().single().credentialState)
        assertEquals(0, refresh.calls.get())
    }
}
