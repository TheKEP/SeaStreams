package com.seastreams.tv.data.debrid

import com.seastreams.tv.data.debrid.api.DebridClients
import com.seastreams.tv.data.debrid.auth.DebridTokenRefresher
import com.seastreams.tv.data.debrid.auth.TokenRefreshers
import com.seastreams.tv.testutil.FakeDebridProviderDao
import com.seastreams.tv.testutil.FakeSecretCipher
import com.seastreams.tv.testutil.TestHttpServer
import com.seastreams.tv.testutil.bearer
import com.seastreams.tv.testutil.debridEndpoints
import com.seastreams.tv.testutil.json
import com.seastreams.tv.testutil.testDebridHttp
import com.seastreams.tv.testutil.unreachableDebridEndpoints
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * `Test` for every service against JSON fixtures served by a real local HTTP server: connected (premium expiry
 * only), invalid key, not premium, rate limited and unreachable. Fixtures carry a username and email that must never
 * reach the result.
 */
class DebridConnectionTesterTest {

    private val now: Instant = Instant.parse("2026-11-23T12:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
    private val expiry = Instant.parse("2027-03-03T12:00:00Z").toEpochMilli()

    /** Which fixture an account call answers: by token (header or Premiumize's `apikey`). */
    private suspend fun ApplicationCall.answer(token: String?, good: String, free: String, bad: Pair<HttpStatusCode, String>) = when (token) {
        GOOD -> json(good)
        FREE -> json(free)
        LIMIT -> json("""{"error":"slow down"}""", HttpStatusCode.TooManyRequests)
        else -> json(bad.second, bad.first)
    }

    private val server = TestHttpServer {
        get("/rd/user") {
            call.answer(
                call.bearer(),
                """{"id":42,"username":"alice","email":"alice@example.com","points":10,"type":"premium","premium":8640000,"expiration":"2027-03-03T12:00:00.000Z"}""",
                """{"id":42,"username":"alice","email":"alice@example.com","type":"free","expiration":"2020-01-01T00:00:00.000Z"}""",
                HttpStatusCode.Unauthorized to """{"error":"bad_token","error_code":8}""",
            )
        }
        get("/ad/v4/user") {
            call.answer(
                call.bearer().takeIf { call.request.queryParameters["agent"] == "SeaStreams" },
                """{"status":"success","data":{"user":{"username":"alice","email":"alice@example.com","isPremium":true,"premiumUntil":1804075200}}}""",
                """{"status":"success","data":{"user":{"username":"alice","email":"alice@example.com","isPremium":false,"premiumUntil":0}}}""",
                HttpStatusCode.OK to """{"status":"error","error":{"code":"AUTH_BAD_APIKEY","message":"The auth apikey is invalid"}}""",
            )
        }
        get("/tb/user/me") {
            call.answer(
                call.bearer(),
                """{"success":true,"data":{"id":7,"email":"alice@example.com","plan":2,"premium_expires_at":"2027-03-03T12:00:00Z"}}""",
                """{"success":true,"data":{"id":7,"email":"alice@example.com","plan":0,"premium_expires_at":null}}""",
                HttpStatusCode.Forbidden to """{"success":false,"error":"BAD_TOKEN","detail":"Invalid token"}""",
            )
        }
        get("/pm/account/info") {
            call.answer(
                call.request.queryParameters["apikey"],
                """{"status":"success","customer_id":"123","premium_until":1804075200,"limit_used":0.1}""",
                """{"status":"success","customer_id":"123","premium_until":false}""",
                HttpStatusCode.OK to """{"status":"error","message":"Not logged in."}""",
            )
        }
        get("/dl/v2/account/infos") {
            call.answer(
                call.bearer(),
                """{"success":true,"value":{"username":"alice","email":"alice@example.com","accountType":1,"premiumLeft":8640000}}""",
                """{"success":true,"value":{"username":"alice","email":"alice@example.com","accountType":0,"premiumLeft":0}}""",
                HttpStatusCode.Unauthorized to """{"success":false,"error":"badToken"}""",
            )
        }
        get("/oc/account/info") {
            call.answer(
                call.bearer(),
                """{"user_id":"u1","email":"alice@example.com","isPremium":true,"expirationDate":"2027-03-03T12:00:00Z"}""",
                """{"user_id":"u1","email":"alice@example.com","isPremium":false}""",
                HttpStatusCode.Unauthorized to """{"error":"Not authorized"}""",
            )
        }
        get("/putio/account/info") {
            call.answer(
                call.bearer(),
                """{"status":"OK","info":{"username":"alice","mail":"alice@example.com","account_active":true,"plan_expiration_date":"2027-03-03T12:00:00"}}""",
                """{"status":"OK","info":{"username":"alice","mail":"alice@example.com","account_active":false}}""",
                HttpStatusCode.Unauthorized to """{"status":"ERROR","error_type":"invalid_grant"}""",
            )
        }
    }

    private fun tester(endpoints: com.seastreams.tv.data.debrid.api.DebridEndpoints = server.debridEndpoints(), repository: DebridSourcesRepository = repo()) =
        DebridConnectionTester(
            DebridClients(testDebridHttp(), endpoints, clock),
            DebridTokenRefresher(repository, TokenRefreshers { null }, clock),
        )

    private val dao = FakeDebridProviderDao()

    private fun repo() = DebridSourcesRepository(dao, FakeSecretCipher(), Dispatchers.Unconfined)

    @AfterEach
    fun tearDown() = server.close()

    @ParameterizedTest
    @EnumSource(DebridServiceKind::class)
    fun `a premium account reports connected with its expiry and nothing else`(kind: DebridServiceKind) = runBlocking<Unit> {
        val result = tester().testKey(kind, GOOD)

        val expected = if (kind == DebridServiceKind.DEBRID_LINK) now.toEpochMilli() + 8_640_000_000L else expiry
        assertEquals(DebridTestResult.Success(expected), result)
        assertFalse(result.toString().contains("alice"), "no username or email kept")
        if (kind != DebridServiceKind.DEBRID_LINK) assertEquals("Connected — premium until 3 Mar 2027", result.message(kind, ZoneOffset.UTC))
    }

    @ParameterizedTest
    @EnumSource(DebridServiceKind::class)
    fun `a rejected key, a free account and rate limiting are reported`(kind: DebridServiceKind) = runBlocking<Unit> {
        val tester = tester()
        assertEquals(DebridTestResult.BadCredentials, tester.testKey(kind, "wrong-key-123"))
        assertEquals(DebridTestResult.NotPremium, tester.testKey(kind, FREE))
        assertEquals(DebridTestResult.RateLimited, tester.testKey(kind, LIMIT))
        assertEquals("Invalid API key or token", DebridTestResult.BadCredentials.message(kind))
        assertEquals("Account isn't premium", DebridTestResult.NotPremium.message(kind))
        assertEquals("Rate limited — try again shortly", DebridTestResult.RateLimited.message(kind))
    }

    @ParameterizedTest
    @EnumSource(DebridServiceKind::class)
    fun `a service that can't be reached says so`(kind: DebridServiceKind) = runBlocking<Unit> {
        val result = tester(unreachableDebridEndpoints).testKey(kind, GOOD)
        assertEquals(DebridTestResult.Unreachable, result)
        assertEquals("Can't reach ${kind.displayName}", result.message(kind))
    }

    @Test
    fun `requests carry the key only in a Bearer header (Premiumize query aside), never cache, never touch torrents`() = runBlocking<Unit> {
        val tester = tester()
        DebridServiceKind.entries.forEach { tester.testKey(it, GOOD) }

        server.requests.forEach { request ->
            val cacheControl = request.header("Cache-Control").orEmpty()
            assertTrue(cacheControl.contains("no-store") && cacheControl.contains("no-cache"), "${request.path}: $cacheControl")
            val path = request.path.lowercase()
            listOf("magnet", "instantavailability", "checkcached", "cache", "addmagnet", "torrents/add", "transfer").forEach {
                assertFalse(path.contains(it), "${request.path} must not touch $it")
            }
            if (request.path.startsWith("/pm/")) {
                assertEquals(null, request.header("Authorization"))
            } else {
                assertEquals("Bearer $GOOD", request.header("Authorization"), request.path)
                assertTrue(request.query.values.flatten().none { it == GOOD }, "${request.path} must not put the key in the URL")
            }
        }
        assertEquals(7, server.requests.size)
    }

    @Test
    fun `testing a saved provider marks a revoked key Sign-in needed and a working one usable again`() = runBlocking<Unit> {
        val repository = repo()
        val tester = tester(repository = repository)
        val id = repository.saveKey(null, DebridServiceKind.TORBOX, "wrong-key-123", null)

        assertEquals(DebridServiceKind.TORBOX to DebridTestResult.BadCredentials, tester.test(id))
        assertEquals(DebridCredentialState.SIGN_IN_NEEDED, repository.providers.first().single().credentialState)

        repository.saveKey(id, DebridServiceKind.TORBOX, GOOD, null)
        repository.markSignInNeeded(id, true)
        assertEquals(DebridServiceKind.TORBOX to DebridTestResult.Success(expiry), tester.test(id))
        assertEquals(DebridCredentialState.API_KEY, repository.providers.first().single().credentialState)
    }

    private companion object {
        const val GOOD = "good-key-0123456789"
        const val FREE = "free-key-0123456789"
        const val LIMIT = "limit-key-0123456789"
    }
}
