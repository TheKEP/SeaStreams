package com.seastreams.tv.data.usenet.newznab

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.seastreams.tv.core.database.entity.NewznabIndexerEntity
import com.seastreams.tv.core.datastore.UsenetSettingsStore
import com.seastreams.tv.data.usenet.UsenetSourcesRepository
import com.seastreams.tv.testutil.FakeNewznabIndexerDao
import com.seastreams.tv.testutil.FakeSecretCipher
import com.seastreams.tv.testutil.FakeUsenetProviderDao
import com.seastreams.tv.testutil.TestHttpServer
import com.seastreams.tv.usenet.cache.NzbFileCache
import com.seastreams.tv.usenet.session.UsenetEngineSetup
import com.seastreams.tv.usenet.session.UsenetStreamRegistry
import com.seastreams.tv.usenet.verify.ReleaseHealth
import com.seastreams.tv.usenet.verify.ReleaseHealthCache
import io.ktor.http.ContentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Clock
import com.seastreams.tv.testutil.usenetSettings

class NewznabHttpTest {
    @TempDir
    lateinit var dir: File

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store by lazy { UsenetSettingsStore(PreferenceDataStoreFactory.create(scope = scope) { File(dir, "s.preferences_pb") }) }
    private val http by lazy { UsenetHttp({ OkHttpClient() }, store) }
    private val caps by lazy { NewznabCapsCache({ dir }) }
    private val client by lazy { NewznabClient(http, caps, Dispatchers.IO) }
    private val nzb = """<nzb><file subject="&quot;a.mkv&quot; yEnc (1/1)"><groups><group>g</group></groups><segments><segment bytes="10" number="1">a@b</segment></segments></file></nzb>"""

    /** Indexers answering [answers] per name: `items` = the search fixture, `empty` = no items, `error` = code 100. */
    private val server = TestHttpServer {
        get("/{name}/api") {
            val name = call.parameters["name"].orEmpty()
            val body = when {
                call.request.queryParameters["t"] == "caps" -> CAPS_FIXTURE
                name.startsWith("error") -> """<error code="100" description="Incorrect user credentials"/>"""
                name.startsWith("empty") -> "<rss><channel></channel></rss>"
                else -> SEARCH_FIXTURE.replace("indexer.invalid", name)
            }
            call.respondText(body, ContentType.Application.Xml)
        }
        get("/nzb/{size}") {
            val size = call.parameters["size"]?.toIntOrNull() ?: 0
            call.respondText(if (size == 0) nzb else nzb + " ".repeat(size), ContentType.Application.Xml)
        }
        get("/html") { call.respondText("<html>Not found</html>", ContentType.Text.Html) }
    }

    @AfterEach
    fun tearDown() {
        server.close()
        scope.cancel()
    }

    private fun endpoint(name: String, priority: Int = 0, backupOnly: Boolean = false, id: Long = name.hashCode().toLong()) =
        NewznabEndpoint(id, name, server.url("/$name/api"), "key-$name", priority, backupOnly)

    @Test
    fun `searches send the Search preset headers and the key, and caps are fetched once`() = runBlocking<Unit> {
        val geek = endpoint("geek")
        val query = NewznabQueries.plan(client.caps(geek), UsenetSearchTarget.Movie("tt15239678", "Dune", 2024)).first()
        client.search(geek, query)
        client.search(geek, query)
        val requests = server.requests.filter { it.path == "/geek/api" }
        assertEquals(1, requests.count { it.queryParam("t") == "caps" }, "caps cached")
        val search = requests.last()
        assertEquals("movie", search.queryParam("t"))
        assertEquals("15239678", search.queryParam("imdbid"))
        assertEquals("2000", search.queryParam("cat"))
        assertEquals("1", search.queryParam("extended"))
        assertEquals("key-geek", search.queryParam("apikey"))
        assertTrue(search.header("User-Agent").orEmpty().startsWith("Prowlarr/"))
    }

    @Test
    fun `an error code becomes a NewznabException and the Test result says why`() = runBlocking<Unit> {
        val bad = endpoint("error1")
        val e = assertThrows<NewznabException> { runBlocking { client.search(bad, NewznabQuery(listOf("t" to "search"), false)) } }
        assertTrue(e.accountProblem)
        assertTrue(client.test(endpoint("geek")) is IndexerTestResult.Success)
        val unreachable = client.test(NewznabEndpoint(9, "Gone", "http://127.0.0.1:1/api", "", 0, false))
        assertEquals("Couldn't reach Gone", unreachable.message)
    }

    @Test
    fun `NZB grabs send the Download headers, respect the size cap, require segments and are cached`() = runBlocking<Unit> {
        val grabber = NzbGrabber(http, NzbFileCache({ dir }), Dispatchers.IO)
        val text = grabber.grab(NzbRequest(server.url("/nzb/0"), "k1"), maxNzbBytes = 1_000_000, maxConcurrentImports = 2)
        assertTrue(text.contains("<segment"))
        assertTrue(server.requests.last().header("User-Agent").orEmpty().startsWith("SABnzbd/"))
        grabber.grab(NzbRequest(server.url("/nzb/0"), "k1"), 1_000_000, 2)
        assertEquals(1, server.hits("/nzb/0"), "the second grab came from the NZB cache")
        assertThrows<ResponseTooLargeException> { runBlocking { grabber.grab(NzbRequest(server.url("/nzb/5000"), "k2"), 2_000, 0) } }
        assertThrows<NotAnNzbException> { runBlocking { grabber.grab(NzbRequest(server.url("/html"), "k3"), 1_000_000, 0) } }
    }

    private fun search(indexers: List<NewznabIndexerEntity>, health: ReleaseHealthCache = ReleaseHealthCache()): UsenetStreamSearch {
        val dao = FakeNewznabIndexerDao().apply { runBlocking { indexers.forEach { insert(it) } } }
        val repository = UsenetSourcesRepository(FakeUsenetProviderDao(), dao, FakeSecretCipher(), Dispatchers.IO)
        return UsenetStreamSearch(repository, client, UsenetStreamRegistry(), health, mockk<UsenetEngineSetup>(relaxed = true), Clock.systemUTC(), usenetSettings())
    }

    private fun row(name: String, priority: Int, backupOnly: Boolean = false, sortOrder: Int = 0) = NewznabIndexerEntity(
        name = name,
        baseUrl = server.baseUrl,
        apiPath = "/$name/api",
        apiKeyCipher = FakeSecretCipher().encrypt("key"),
        priority = priority,
        backupOnly = backupOnly,
        enabled = true,
        sortOrder = sortOrder,
    )

    private val movie = UsenetSearchTarget.Movie("tt15239678", "Dune Part Two", 2024)

    @Test
    fun `better priority tiers come first, duplicates collapse, passworded releases rank last, backups wait`() = runBlocking<Unit> {
        val result = search(listOf(row("slow", 5, sortOrder = 0), row("fast", 0, sortOrder = 1), row("spare", 0, backupOnly = true, sortOrder = 2)))
            .search(movie)
        assertFalse(result.failed)
        // Each indexer returns the same two releases: kept once, from the tier-0 indexer. The passworded 1080p one is
        // kept (its NZB may carry the 7z password) but ranks after the clean 2160p one.
        assertEquals(2, result.streams.size)
        assertTrue(result.streams.all { it.description.contains("fast") })
        assertTrue(result.streams[1].description.startsWith("Dune Part Two 2024 1080p"))
        val stream = result.streams.first()
        assertEquals("Usenet · 2160p", stream.label)
        assertEquals("usenet://builtin", stream.addonUrl)
        assertTrue(UsenetStreamRegistry.isUsenet(stream.url))
        assertFalse(stream.url.contains("key"), "no API key in the stream URL")
        assertEquals(0, server.hits("/spare/api"), "backup-only indexers wait while others have results")
        // Search reads only the XML: no NZB grab (t=get), so indexer grab quotas are spent only on Play.
        assertTrue(server.requests.none { it.queryParam("t") == "get" || it.path.startsWith("/nzb") })
        assertTrue(server.requests.all { it.queryParam("t") in setOf("caps", "movie", "search") })
    }

    @Test
    fun `backup-only indexers are searched when the others find nothing`() = runBlocking<Unit> {
        val result = search(listOf(row("empty1", 0), row("spare", 0, backupOnly = true, sortOrder = 1))).search(movie)
        assertEquals(2, result.streams.size)
        assertTrue(result.streams.all { it.description.contains("spare") })
    }

    @Test
    fun `failing indexers are reported and releases known broken are dropped`() = runBlocking<Unit> {
        assertTrue(search(listOf(row("error1", 0))).search(movie).failed)
        val health = ReleaseHealthCache()
        val first = search(listOf(row("fast", 0)), health).search(movie).streams.first()
        health.record(checkNotNull(UsenetStreamRegistry.tokenOf(first.url)), ReleaseHealth.Broken("missing"))
        val left = search(listOf(row("fast", 0)), health).search(movie).streams
        assertEquals(1, left.size)
        assertTrue(left.none { it.url == first.url })
    }
}
