package com.seastreams.tv.data.addon

import com.seastreams.tv.core.database.entity.AddonEntity
import com.seastreams.tv.core.network.HttpCachePolicy
import com.seastreams.tv.core.network.ApiEndpoints
import com.seastreams.tv.core.network.NetworkModule
import com.seastreams.tv.core.network.RateLimitRetryInterceptor
import com.seastreams.tv.core.network.StremioAddonClient
import com.seastreams.tv.data.UserFacingException
import com.seastreams.tv.testutil.FakeAddonDao
import com.seastreams.tv.testutil.TestHttpServer
import io.ktor.client.HttpClient
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import kotlinx.coroutines.test.runTest
import okhttp3.Cache
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class AddonOrderingTest {

    private val order = listOf("a", "b", "c", "d")

    @Test
    fun `move up and down by one`() {
        assertEquals(listOf("a", "c", "b", "d"), AddonOrdering.move(order, "c", -1))
        assertEquals(listOf("a", "c", "b", "d"), AddonOrdering.move(order, "b", 1))
    }

    @Test
    fun `move clamps at the ends and ignores unknown urls`() {
        assertEquals(order, AddonOrdering.move(order, "a", -1))
        assertEquals(order, AddonOrdering.move(order, "d", 5))
        assertEquals(listOf("b", "c", "d", "a"), AddonOrdering.move(order, "a", 99))
        assertEquals(order, AddonOrdering.move(order, "zzz", 1))
    }

    @Test
    fun `reconcile drops unknown and duplicate urls and appends omitted ones`() {
        assertEquals(listOf("d", "b", "a", "c"), AddonOrdering.reconcile(order, listOf("d", "x", "b", "d")))
        assertEquals(listOf("c", "b", "a", "d"), AddonOrdering.reconcile(order, listOf("c", "b", "a", "d")))
        assertEquals(order, AddonOrdering.reconcile(order, emptyList()))
    }
}

class AddonRepositoryTest {

    @TempDir
    lateinit var cacheDir: File

    private val clock = Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000), ZoneOffset.UTC)
    private lateinit var server: TestHttpServer
    private lateinit var http: HttpClient
    private lateinit var dao: FakeAddonDao
    private lateinit var repository: AddonRepository

    @BeforeEach
    fun setUp() {
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
        dao = FakeAddonDao()
        repository = AddonRepository(dao, StremioAddonClient(http), clock)
    }

    @AfterEach
    fun tearDown() {
        http.close()
        server.close()
    }

    private fun addon(url: String, order: Int) = AddonEntity(url, "id.$url", url, null, true, order, "{}", 0)

    @Test
    fun `seeding installs TMDB and OpenSubtitles enabled, and never overwrites`() = runTest {
        repository.seedDefaults()
        assertEquals(listOf(BuiltInAddons.TMDB_URL, BuiltInAddons.OPENSUBTITLES_URL), dao.ordered.map { it.manifestUrl })
        assertTrue(dao.ordered.all { it.enabled })

        dao.rename(BuiltInAddons.OPENSUBTITLES_URL, "Subs")
        repository.seedDefaults()
        assertEquals("Subs", dao.rows.value.getValue(BuiltInAddons.OPENSUBTITLES_URL).customName)
    }

    @Test
    fun `install fetches the manifest and appends it to the end`() = runTest {
        repository.seedDefaults()
        val installed = repository.install(server.url("/good"))

        assertEquals(server.url("/good/manifest.json"), installed.manifestUrl)
        assertEquals("Good Addon", installed.name)
        assertEquals(2, installed.sortOrder)
        assertEquals(clock.millis(), installed.installedAtMillis)
        val duplicate = assertThrows<UserFacingException> { repository.install(server.url("/good/manifest.json")) }
        assertEquals("That addon is already installed", duplicate.message)
    }

    @Test
    fun `install reports invalid manifests and HTTP errors without saving`() = runTest {
        assertTrue(assertThrows<UserFacingException> { repository.install(server.url("/bad/manifest.json")) }
            .message.orEmpty().startsWith("Invalid addon manifest"))
        assertEquals("The addon server answered HTTP 404",
            assertThrows<UserFacingException> { repository.install(server.url("/gone/manifest.json")) }.message)
        assertThrows<UserFacingException> { repository.install("ftp://nope") }
        assertTrue(dao.ordered.isEmpty())
    }

    @Nested
    inner class Reordering {
        @BeforeEach
        fun seed() {
            dao.rows.value = listOf(addon("a", 0), addon("b", 1), addon("c", 2), addon("d", 3)).associateBy { it.manifestUrl }
        }

        @Test
        fun `move rewrites dense sort orders`() = runTest {
            repository.move("c", -2)
            assertEquals(listOf("c", "a", "b", "d"), dao.ordered.map { it.manifestUrl })
            assertEquals(listOf(0, 1, 2, 3), dao.ordered.map { it.sortOrder })
        }

        @Test
        fun `reorder applies a full client order`() = runTest {
            repository.reorder(listOf("d", "c", "b", "a"))
            assertEquals(listOf("d", "c", "b", "a"), dao.ordered.map { it.manifestUrl })
        }

        @Test
        fun `remove closes the gap`() = runTest {
            repository.remove("b")
            assertEquals(listOf("a" to 0, "c" to 1, "d" to 2), dao.ordered.map { it.manifestUrl to it.sortOrder })
        }

        @Test
        fun `moving an unknown addon fails`() = runTest {
            assertThrows<UserFacingException> { repository.move("zzz", 1) }
        }
    }

    @Test
    fun `built-in TMDB cannot be removed or disabled but can be renamed`() = runTest {
        repository.seedDefaults()
        assertThrows<UserFacingException> { repository.remove(BuiltInAddons.TMDB_URL) }
        assertThrows<UserFacingException> { repository.setEnabled(BuiltInAddons.TMDB_URL, false) }
        repository.setEnabled(BuiltInAddons.OPENSUBTITLES_URL, false)
        repository.rename(BuiltInAddons.TMDB_URL, "  Metadata  ")

        assertEquals("Metadata", dao.rows.value.getValue(BuiltInAddons.TMDB_URL).customName)
        assertEquals(false, dao.rows.value.getValue(BuiltInAddons.OPENSUBTITLES_URL).enabled)
        repository.rename(BuiltInAddons.TMDB_URL, "")
        assertNull(dao.rows.value.getValue(BuiltInAddons.TMDB_URL).customName)
    }
}
