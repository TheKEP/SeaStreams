package com.seastreams.tv.data.addon

import com.seastreams.tv.testutil.debridAddonState
import com.seastreams.tv.testutil.usenetAddonState
import com.seastreams.tv.core.database.entity.AddonEntity
import com.seastreams.tv.core.network.ApiEndpoints
import com.seastreams.tv.core.network.HttpCachePolicy
import com.seastreams.tv.core.network.NetworkModule
import com.seastreams.tv.core.network.RateLimitRetryInterceptor
import com.seastreams.tv.core.network.StremioAddonClient
import com.seastreams.tv.testutil.FakeAddonDao
import com.seastreams.tv.testutil.FakeProfileDao
import com.seastreams.tv.testutil.TestHttpServer
import com.seastreams.tv.testutil.testProfile
import io.ktor.client.HttpClient
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import okhttp3.Cache
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Shared setup for [AddonRepository] tests: a local manifest server and two existing profiles,
 * [PROFILE_A] and [PROFILE_B], each starting with no addons.
 */
abstract class AddonRepositoryTestBase {

    @TempDir
    lateinit var cacheDir: File

    protected val clock: Clock = Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000), ZoneOffset.UTC)
    protected lateinit var server: TestHttpServer
    private lateinit var http: HttpClient
    protected lateinit var profileDao: FakeProfileDao
    protected lateinit var dao: FakeAddonDao
    protected lateinit var repository: AddonRepository

    /** Whether Usenet streaming counts as configured (the built-in `Usenet` entry is listed only then). */
    protected val usenetConfigured = kotlinx.coroutines.flow.MutableStateFlow(false)
    protected val debridListed = kotlinx.coroutines.flow.MutableStateFlow(false)

    @BeforeEach
    fun setUpRepository() {
        server = TestHttpServer {
            get("/good/manifest.json") {
                call.respondText(
                    """{"id":"com.example.good","version":"1.0.0","name":"Good Addon","resources":["stream"],"types":["movie"]}""",
                    ContentType.Text.Plain,
                )
            }
            get("/bad/manifest.json") { call.respondText("""{"name":"missing id"}""", ContentType.Application.Json) }
            get("/gone/manifest.json") { call.respondText("", status = HttpStatusCode.NotFound) }
        }
        val endpoints = ApiEndpoints(tmdbBaseUrl = server.url("/3"), mdbListBaseUrl = server.url("/mdb"))
        val okHttp = NetworkModule.buildOkHttpClient(
            Cache(cacheDir, 1024L * 1024),
            HttpCachePolicy(endpoints),
            RateLimitRetryInterceptor(sleeper = { }),
        )
        http = NetworkModule.buildKtorClient(okHttp, NetworkModule.provideJson())
        profileDao = FakeProfileDao()
        profileDao.rows.value = listOf(testProfile(PROFILE_A, isKids = false), testProfile(PROFILE_B, isKids = true))
        dao = FakeAddonDao(profileIds = { profileDao.rows.value.map { it.id } })
        repository = AddonRepository(dao, profileDao, StremioAddonClient(http), clock, usenetAddonState(usenetConfigured), debridAddonState(debridListed))
    }

    /** Seeds [profileId]'s built-in defaults the way `ProfileRepository.create` does; present URLs are left untouched. */
    protected suspend fun seedDefaults(profileId: Long) = dao.insertIfAbsent(BuiltInAddons.defaults(profileId, clock.millis()))

    @AfterEach
    fun tearDownRepository() {
        http.close()
        server.close()
    }

    protected fun addon(profileId: Long, url: String, order: Int) =
        AddonEntity(profileId, url, "id.$url", url, null, true, order, "{}", 0)

    protected fun urls(profileId: Long): List<String> = dao.ordered(profileId).map { it.manifestUrl }

    protected fun row(profileId: Long, url: String): AddonEntity = dao.rows.value.getValue(profileId to url)

    companion object {
        const val PROFILE_A = 1L
        const val PROFILE_B = 2L
    }
}
