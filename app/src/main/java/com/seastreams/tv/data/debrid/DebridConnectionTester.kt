package com.seastreams.tv.data.debrid

import com.seastreams.tv.BuildConfig
import com.seastreams.tv.data.debrid.api.DebridAccount
import com.seastreams.tv.data.debrid.api.DebridAuthException
import com.seastreams.tv.data.debrid.api.DebridClients
import com.seastreams.tv.data.debrid.api.DebridEndpoints
import com.seastreams.tv.data.debrid.api.DebridRateLimitedException
import com.seastreams.tv.data.debrid.api.DebridServiceException
import com.seastreams.tv.data.debrid.api.NotPremiumException
import com.seastreams.tv.data.debrid.auth.DebridTokenRefresher
import com.seastreams.tv.data.debrid.auth.DeviceFlowProvider
import com.seastreams.tv.data.debrid.auth.DeviceFlows
import com.seastreams.tv.data.debrid.auth.TokenRefreshers
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** A `Test` outcome (PRD §11 `Debrid providers`): premium expiry only, never an account name or email. */
sealed interface DebridTestResult {
    data class Success(val premiumUntilMillis: Long?) : DebridTestResult

    data object BadCredentials : DebridTestResult

    data object NotPremium : DebridTestResult

    data object RateLimited : DebridTestResult

    data object Unreachable : DebridTestResult

    data object Unexpected : DebridTestResult

    /** The toast / portal wording for [kind]. */
    fun message(kind: DebridServiceKind, zone: ZoneId = ZoneId.systemDefault()): String = when (this) {
        is Success -> premiumUntilMillis
            ?.let { "Connected — premium until " + EXPIRY_FORMAT.format(Instant.ofEpochMilli(it).atZone(zone)) }
            ?: "Connected"
        BadCredentials -> "Invalid API key or token"
        NotPremium -> "Account isn't premium"
        RateLimited -> "Rate limited — try again shortly"
        Unreachable -> "Can't reach ${kind.displayName}"
        Unexpected -> "${kind.displayName} gave an unexpected answer"
    }
}

private val EXPIRY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US)

/**
 * Checks a Debrid credential against the service's account endpoint (ARCHITECTURE §12). A saved provider is tested
 * through the [DebridTokenRefresher] (so a Real-Debrid sign-in is renewed when due, and a rejected credential marks the
 * row *Sign-in needed*); a key typed in the portal is tested as is.
 */
@Singleton
class DebridConnectionTester @Inject constructor(
    private val clients: DebridClients,
    private val refresher: DebridTokenRefresher,
) {
    suspend fun test(providerId: Long): Pair<DebridServiceKind?, DebridTestResult> {
        var kind: DebridServiceKind? = null
        val result = outcome {
            refresher.withCredentials(providerId) { credentials ->
                kind = credentials.kind
                clients.of(credentials.kind).account(credentials.accessToken)
            }
        }
        return kind to result
    }

    suspend fun testKey(kind: DebridServiceKind, key: String): DebridTestResult =
        outcome { clients.of(kind).account(DebridSourceValidation.key(kind, key)) }

    private suspend fun outcome(check: suspend () -> DebridAccount): DebridTestResult = try {
        val account = check()
        if (account.premium) DebridTestResult.Success(account.premiumUntilMillis) else DebridTestResult.NotPremium
    } catch (e: DebridAuthException) {
        DebridTestResult.BadCredentials
    } catch (e: NotPremiumException) {
        DebridTestResult.NotPremium
    } catch (e: DebridRateLimitedException) {
        DebridTestResult.RateLimited
    } catch (e: DebridServiceException) {
        DebridTestResult.Unexpected
    } catch (e: IOException) {
        DebridTestResult.Unreachable
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DebridModule {
    @Provides
    @Singleton
    fun provideDebridEndpoints(): DebridEndpoints = DebridEndpoints()

    @Provides
    @Singleton
    fun provideDebridClientIds(): DebridClientIds =
        DebridClientIds(debridLink = BuildConfig.DEBRID_LINK_CLIENT_ID, putIo = BuildConfig.PUTIO_CLIENT_ID)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DebridBindings {
    @Binds
    abstract fun bindDeviceFlowProvider(flows: DeviceFlows): DeviceFlowProvider

    @Binds
    abstract fun bindTokenRefreshers(flows: DeviceFlows): TokenRefreshers
}
