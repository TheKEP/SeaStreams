package com.seastreams.tv.core.webserver

import com.seastreams.tv.data.catalog.DiscoverSnapshotStore
import com.seastreams.tv.data.catalog.LoadedCatalogStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.cash.turbine.test
import com.seastreams.tv.core.database.entity.CatalogPlacement
import com.seastreams.tv.core.datastore.SettingsDataStore
import com.seastreams.tv.core.network.ApiEndpoints
import com.seastreams.tv.core.network.HttpCachePolicy
import com.seastreams.tv.core.network.MdbListApiService
import com.seastreams.tv.core.network.NetworkModule
import com.seastreams.tv.core.network.RateLimitRetryInterceptor
import com.seastreams.tv.core.network.StremioAddonClient
import com.seastreams.tv.data.addon.AddonRepository
import com.seastreams.tv.data.addon.BuiltInAddons
import com.seastreams.tv.data.catalog.CatalogRepository
import com.seastreams.tv.data.profile.ProfileRepository
import com.seastreams.tv.data.setup.TmdbKeyRepository
import com.seastreams.tv.domain.model.MovieCertification
import com.seastreams.tv.domain.model.TvCertification
import com.seastreams.tv.domain.usecase.ImportMdbListUseCase
import com.seastreams.tv.domain.usecase.LibraryImportResult
import com.seastreams.tv.domain.usecase.LibraryTarget
import com.seastreams.tv.testutil.FakeAddonDao
import com.seastreams.tv.testutil.FakeCatalogDao
import com.seastreams.tv.testutil.FakeProfileDao
import com.seastreams.tv.testutil.TestHttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.Cache
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.Socket
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Drives the real Ktor CIO portal over HTTP, exactly as the phone's browser does. */
class PortalServerTest {

    @TempDir
    lateinit var dir: File

    private val json = Json { ignoreUnknownKeys = true }
    private val clock = Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000), ZoneOffset.UTC)
    private lateinit var scope: CoroutineScope
    private lateinit var upstream: TestHttpServer
    private lateinit var appHttp: HttpClient
    private lateinit var browser: HttpClient
    private lateinit var settings: SettingsDataStore
    private lateinit var tmdbKeys: TmdbKeyRepository
    private lateinit var profileDao: FakeProfileDao
    private lateinit var addonDao: FakeAddonDao
    private lateinit var catalogDao: FakeCatalogDao
    private val importUseCase = mockk<ImportMdbListUseCase>()
    private lateinit var portal: LocalPortalServer
    private lateinit var base: String
    private var port = 0

    @BeforeEach
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        settings = SettingsDataStore(PreferenceDataStoreFactory.create(scope = scope) { File(dir, "s.preferences_pb") })
        upstream = TestHttpServer {
            get("/addon/manifest.json") {
                call.respondText("""{"id":"com.example.torrent","version":"1.0.0","name":"Streams","resources":["stream"]}""")
            }
            get("/mdb/lists/owner/best-sci-fi/json") {
                call.respondText("""[{"id":603,"title":"The Matrix","mediatype":"movie"}]""", ContentType.Application.Json)
            }
        }
        val endpoints = ApiEndpoints(tmdbBaseUrl = upstream.url("/3"), mdbListBaseUrl = upstream.url("/mdb"))
        val okHttp = NetworkModule.buildOkHttpClient(
            Cache(File(dir, "cache"), 1024L * 1024),
            HttpCachePolicy(endpoints),
            RateLimitRetryInterceptor(sleeper = { }),
        )
        appHttp = NetworkModule.buildKtorClient(okHttp, NetworkModule.provideJson())

        tmdbKeys = TmdbKeyRepository(settings) { key -> key == VALID_KEY }
        profileDao = FakeProfileDao()
        addonDao = FakeAddonDao()
        catalogDao = FakeCatalogDao()
        val controller = PortalController(
            tmdbKeys = tmdbKeys,
            profiles = ProfileRepository(profileDao, settings, settings, clock, DiscoverSnapshotStore(), LoadedCatalogStore(Clock.systemUTC())),
            addons = AddonRepository(addonDao, StremioAddonClient(appHttp), clock),
            catalogs = CatalogRepository(catalogDao, MdbListApiService(appHttp, endpoints, NetworkModule.provideJson()), clock),
            importMdbList = importUseCase,
        )
        portal = LocalPortalServer(controller)
        port = requireNotNull(portal.start(port = 0, host = "127.0.0.1"))
        base = "http://127.0.0.1:$port"
        browser = HttpClient(OkHttp)
    }

    @AfterEach
    fun tearDown() {
        portal.stop()
        browser.close()
        appHttp.close()
        upstream.close()
        scope.cancel()
    }

    private suspend fun postJson(path: String, body: String): HttpResponse = browser.post(base + path) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun message(response: HttpResponse): ApiMessage = json.decodeFromString(response.bodyAsText())

    private suspend fun state(): PortalState = json.decodeFromString(browser.get("$base/api/state").bodyAsText())

    @Test
    fun `serves the portal page with the script and avatars inlined`() = runTest {
        val page = browser.get("$base/")
        val html = page.bodyAsText()

        assertEquals(200, page.status.value)
        assertTrue(html.contains("<title>SeaStreams Portal</title>"))
        assertTrue(html.contains("window.AVATARS = ["))
        assertTrue(html.contains("function refresh()"))
        assertFalse(html.contains("/*SCRIPT*/"))
    }

    @Test
    fun `TMDB key form rejects bad keys and saves good ones, which the TV observes`() = runTest {
        tmdbKeys.hasValidKey.test {
            assertEquals(false, awaitItem())

            val rejected = postJson("/api/tmdb-key", """{"apiKey":"wrong"}""")
            assertEquals(400, rejected.status.value)
            assertEquals("TMDB rejected that API key", message(rejected).message)

            val accepted = postJson("/api/tmdb-key", """{"apiKey":"  $VALID_KEY  "}""")
            assertEquals(200, accepted.status.value)
            assertTrue(message(accepted).ok)
            // This is the flow the TV onboarding screen collects: it unlocks Continue reactively.
            assertEquals(true, awaitItem())
        }
        assertEquals(VALID_KEY, settings.tmdbApiKey.first())
        assertEquals(TmdbKeyRepository.mask(VALID_KEY), state().tmdbKeyMasked)
    }

    @Test
    fun `profile forms require a key, enforce kids ceilings and the five-profile cap`() = runTest {
        val tooEarly = postJson("/api/profiles", """{"name":"John"}""")
        assertEquals(400, tooEarly.status.value)
        settings.setTmdbApiKey(VALID_KEY)

        val kids = postJson(
            "/api/profiles",
            """{"name":" Mia ","avatarIndex":3,"isKids":true,"movieCeiling":"PG","tvCeiling":"TV-Y7","allowUnrated":true}""",
        )
        assertEquals(200, kids.status.value, kids.bodyAsText())
        val mia = profileDao.rows.value.single()
        assertEquals("Mia", mia.name)
        assertEquals(MovieCertification.PG, mia.movieCeiling)
        assertEquals(TvCertification.TV_Y7, mia.tvCeiling)
        assertTrue(mia.allowUnrated)

        val tooMature = postJson("/api/profiles/${mia.id}", """{"name":"Mia","isKids":true,"movieCeiling":"R"}""")
        assertEquals(400, tooMature.status.value)

        val edited = postJson("/api/profiles/${mia.id}", """{"name":"Mia","avatarIndex":3,"isKids":false}""")
        assertEquals(200, edited.status.value)
        assertEquals(null, profileDao.rows.value.single().toAgeRestriction())

        repeat(4) { assertEquals(200, postJson("/api/profiles", """{"name":"P$it"}""").status.value) }
        val sixth = postJson("/api/profiles", """{"name":"Sixth"}""")
        assertEquals("You can have at most 5 profiles", message(sixth).message)
        assertEquals(5, state().profiles.size)

        assertEquals(200, browser.post("$base/api/profiles/${mia.id}/delete") { contentType(ContentType.Application.Json) }.status.value)
        assertEquals(4, profileDao.rows.value.size)
    }

    @Test
    fun `addon forms install, toggle, rename, reorder and remove`() = runTest {
        addonDao.insertIfAbsent(BuiltInAddons.defaults(0))
        val url = upstream.url("/addon/manifest.json")

        assertEquals(200, postJson("/api/addons", """{"manifestUrl":"$url"}""").status.value)
        assertEquals(listOf(BuiltInAddons.TMDB_URL, BuiltInAddons.OPENSUBTITLES_URL, url), state().addons.map { it.manifestUrl })

        postJson("/api/addons/move", """{"manifestUrl":"$url","offset":-1}""")
        postJson("/api/addons/enabled", """{"manifestUrl":"$url","enabled":false}""")
        postJson("/api/addons/rename", """{"manifestUrl":"$url","name":"My streams"}""")
        val after = state().addons
        assertEquals(listOf(BuiltInAddons.TMDB_URL, url, BuiltInAddons.OPENSUBTITLES_URL), after.map { it.manifestUrl })
        assertEquals("My streams", after[1].name)
        assertEquals("Streams", after[1].manifestName)
        assertFalse(after[1].enabled)
        assertTrue(after[0].builtIn)

        postJson("/api/addons/reorder", """{"manifestUrls":["${BuiltInAddons.OPENSUBTITLES_URL}","$url"]}""")
        assertEquals(listOf(BuiltInAddons.OPENSUBTITLES_URL, url, BuiltInAddons.TMDB_URL), state().addons.map { it.manifestUrl })

        assertEquals(400, postJson("/api/addons/remove", """{"manifestUrl":"${BuiltInAddons.TMDB_URL}"}""").status.value)
        assertEquals(200, postJson("/api/addons/remove", """{"manifestUrl":"$url"}""").status.value)
        assertEquals(2, state().addons.size)
    }

    @Test
    fun `catalog form validates the list and assigns it to a tab`() = runTest {
        val added = postJson("/api/catalogs", """{"url":"mdblist.com/lists/owner/best-sci-fi?x=1","placement":"MOVIES"}""")
        assertEquals(200, added.status.value, added.bodyAsText())

        val row = catalogDao.rows.value.single()
        assertEquals("https://mdblist.com/lists/owner/best-sci-fi", row.listUrl)
        assertEquals("Best Sci Fi", row.title)
        assertEquals(CatalogPlacement.MOVIES, row.placement)

        val duplicate = postJson("/api/catalogs", """{"url":"https://mdblist.com/lists/owner/best-sci-fi","placement":"MOVIES"}""")
        assertEquals("That list is already on this tab", message(duplicate).message)
        assertEquals(400, postJson("/api/catalogs", """{"url":"https://example.com/x","placement":"HOME"}""").status.value)
        assertEquals(400, postJson("/api/catalogs", """{"url":"mdblist.com/lists/owner/missing","placement":"HOME"}""").status.value)
        assertEquals(400, postJson("/api/catalogs", """{"url":"mdblist.com/lists/owner/best-sci-fi","placement":"SIDEBAR"}""").status.value)

        browser.post("$base/api/catalogs/${row.id}/delete") { contentType(ContentType.Application.Json) }
        assertTrue(catalogDao.rows.value.isEmpty())
    }

    @Test
    fun `library import form passes profile and section to the use case`() = runTest {
        coEvery { importUseCase.invoke(7L, any(), LibraryTarget.TV_SHOWS) } returns LibraryImportResult(12, 1, 3, 2, 0)

        val response = postJson("/api/library/import", """{"url":"https://mdblist.com/lists/a/b","profileId":7,"target":"TV_SHOWS"}""")

        assertEquals(
            "Added 12 titles, 1 already in library, 2 blocked by age rating, 3 of the other type skipped",
            message(response).message,
        )
        coVerify(exactly = 1) { importUseCase.invoke(7L, "https://mdblist.com/lists/a/b", LibraryTarget.TV_SHOWS) }
    }

    @Test
    fun `rejects cross-site form posts, malformed JSON and foreign Host headers`() = runTest {
        val formPost = browser.post("$base/api/tmdb-key") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("apiKey=$VALID_KEY")
        }
        assertEquals(415, formPost.status.value)
        assertEquals(400, postJson("/api/tmdb-key", "{not json").status.value)
        assertEquals(null, settings.tmdbApiKey.first())

        val statusLine = Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().write(
                "GET /api/state HTTP/1.1\r\nHost: attacker.example:$port\r\nConnection: close\r\n\r\n".toByteArray(),
            )
            socket.getInputStream().bufferedReader().readLine()
        }
        assertTrue(statusLine.contains("403"), statusLine)
    }

    @Test
    fun `trusted hosts are IP literals and localhost only`() {
        listOf("192.168.1.20", "127.0.0.1", "localhost", "[fe80::1]", "::1").forEach { assertTrue(isTrustedPortalHost(it), it) }
        listOf("attacker.example", "192.168.1", "999.1.1.1", "tv.local", "").forEach { assertFalse(isTrustedPortalHost(it), it) }
    }

    @Test
    fun `starting twice reuses the running server`() {
        assertEquals(port, portal.start(port = 0, host = "127.0.0.1"))
        assertEquals(PortalStatus.Running(port), portal.status.value)
    }

    private companion object {
        const val VALID_KEY = "0123456789abcdef0123456789abcdef"
    }
}
