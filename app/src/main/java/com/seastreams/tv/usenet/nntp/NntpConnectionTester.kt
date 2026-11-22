package com.seastreams.tv.usenet.nntp

import com.seastreams.tv.core.util.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runInterruptible
import javax.inject.Inject
import javax.inject.Singleton

/** What the provider `Test` button reports (TV Sources tab and Web Portal). [reply] is the server's own words. */
sealed interface ProviderTestResult {
    val message: String

    data class Success(val name: String, val latencyMs: Long) : ProviderTestResult {
        override val message = "$name: connected and signed in ($latencyMs ms)"
    }

    data class BadLogin(val name: String, val reply: String? = null) : ProviderTestResult {
        override val message = "$name rejected the username or password" + said(reply)
    }

    data class ConnectionLimit(val name: String, val reply: String? = null) : ProviderTestResult {
        override val message = "$name is at its connection limit. Close other Usenet apps or lower Max connections" + said(reply)
    }

    data class Unreachable(val name: String, val reason: String) : ProviderTestResult {
        override val message = "Couldn't reach $name: $reason"
    }

    private companion object {
        fun said(reply: String?) = reply?.let { ". Server said: $it" }.orEmpty()
    }
}

/**
 * The provider `Test` button: one dial + TLS + sign-in + `DATE` round trip, never through a pool, so a test
 * can't disturb playback's connections. Runs on [io] and is interruptible.
 */
@Singleton
class NntpConnectionTester internal constructor(
    private val io: CoroutineDispatcher,
    private val opener: SocketOpener,
    private val clock: () -> Long,
) {
    @Inject
    constructor(@IoDispatcher io: CoroutineDispatcher) : this(io, DefaultSocketOpener, monotonicMillis)

    suspend fun test(endpoint: NntpEndpoint, dialTimeoutMs: Int = DEFAULT_DIAL_TIMEOUT_MS): ProviderTestResult =
        runInterruptible(io) { testBlocking(endpoint, dialTimeoutMs) }

    internal fun testBlocking(endpoint: NntpEndpoint, dialTimeoutMs: Int): ProviderTestResult {
        val settings = NntpEngineSettings(dialTimeoutMs = dialTimeoutMs, stallTimeoutMs = dialTimeoutMs)
        val started = clock()
        return try {
            val date = NntpConnection.connect(endpoint, settings, opener, clock).use { it.date() }
            // DATE is optional for some servers (500 = unsupported), so most answers prove the session works; a
            // 480/481/482/502 means the server still won't serve this account (e.g. no username was given).
            when {
                date.code !in REFUSALS -> ProviderTestResult.Success(endpoint.name, clock() - started)
                isConnectionLimitText(date.text) -> ProviderTestResult.ConnectionLimit(endpoint.name, date.describe())
                else -> ProviderTestResult.BadLogin(endpoint.name, date.describe())
            }
        } catch (e: NntpException) {
            when (e.kind) {
                NntpErrorKind.AUTH_FAILED -> ProviderTestResult.BadLogin(endpoint.name, e.reply)
                NntpErrorKind.CONNECTION_LIMIT -> ProviderTestResult.ConnectionLimit(endpoint.name, e.reply)
                else -> ProviderTestResult.Unreachable(endpoint.name, e.message ?: "no answer")
            }
        } catch (e: java.io.IOException) {
            ProviderTestResult.Unreachable(endpoint.name, e.message ?: "no answer")
        }
    }

    companion object {
        const val DEFAULT_DIAL_TIMEOUT_MS = 15_000
        private val REFUSALS = setOf(480, 481, 482, 502)
    }
}
