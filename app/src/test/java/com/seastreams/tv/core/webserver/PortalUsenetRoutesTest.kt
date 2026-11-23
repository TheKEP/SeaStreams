package com.seastreams.tv.core.webserver

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.seastreams.tv.core.datastore.UsenetSettingsStore
import com.seastreams.tv.data.usenet.UsenetSourcesRepository
import com.seastreams.tv.data.usenet.headers.HeaderPreset
import com.seastreams.tv.data.usenet.headers.HeaderVersionUpdater
import com.seastreams.tv.domain.usenet.UsenetProfile
import com.seastreams.tv.usenet.nntp.NntpConnectionTester
import com.seastreams.tv.testutil.FakeNewznabIndexerDao
import com.seastreams.tv.testutil.FakeNntpServer
import com.seastreams.tv.testutil.FakeSecretCipher
import com.seastreams.tv.testutil.FakeUsenetProviderDao
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.Socket

/** Drives the Usenet Sources endpoints of the real Ktor CIO portal over HTTP, as the phone's browser does. */
class PortalUsenetRoutesTest {

    @TempDir
    lateinit var dir: File

    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var scope: CoroutineScope
    private lateinit var store: UsenetSettingsStore
    private val providerDao = FakeUsenetProviderDao()
    private val indexerDao = FakeNewznabIndexerDao()
    private val cipher = FakeSecretCipher()
    private val updater = mockk<HeaderVersionUpdater>()
    private lateinit var portal: LocalPortalServer
    private lateinit var browser: HttpClient
    private lateinit var base: String

    @BeforeEach
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        store = UsenetSettingsStore(PreferenceDataStoreFactory.create(scope = scope) { File(dir, "s.preferences_pb") })
        val repository = UsenetSourcesRepository(providerDao, indexerDao, cipher, Dispatchers.IO)
        portal = LocalPortalServer(mockk<PortalController>(relaxed = true), PortalUsenetController(repository, store, updater, NntpConnectionTester(Dispatchers.IO), mockk(relaxed = true)))
        val port = requireNotNull(portal.start(port = 0, host = "127.0.0.1"))
        base = "http://127.0.0.1:$port"
        browser = HttpClient(OkHttp)
    }

    @AfterEach
    fun tearDown() {
        portal.stop()
        browser.close()
        scope.cancel()
    }

    private suspend fun post(path: String, body: String): HttpResponse = browser.post(base + path) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun message(response: HttpResponse): ApiMessage = json.decodeFromString(response.bodyAsText())

    private suspend fun stateText(): String = browser.get("$base/api/usenet").bodyAsText()

    private suspend fun state(): UsenetPortalState = json.decodeFromString(stateText())

    @Test
    fun `providers are added from the phone and the password is never sent back`() = runTest {
        val added = post("/api/usenet/providers", """{"name":"Eweka","host":"news.eweka.nl","username":"me","password":"hunter2","maxConnections":50}""")
        assertEquals(200, added.status.value)
        assertEquals("Provider added", message(added).message)

        val text = stateText()
        assertFalse(text.contains("hunter2"), "password leaked to the browser")
        assertFalse(text.contains(cipher.encrypt("hunter2")), "ciphertext leaked to the browser")
        val provider = state().providers.single()
        assertTrue(provider.hasPassword)
        assertEquals(50, provider.maxConnections)
        assertEquals(cipher.encrypt("hunter2"), providerDao.rows.value.single().passwordCipher)
    }

    @Test
    fun `editing with a blank password keeps it, and invalid input answers 400 with a readable message`() = runTest {
        post("/api/usenet/providers", """{"name":"Eweka","host":"news.eweka.nl","password":"hunter2"}""")
        val id = state().providers.single().id

        val edited = post("/api/usenet/providers", """{"id":$id,"name":"Eweka NL","host":"news.eweka.nl","password":""}""")
        assertEquals(200, edited.status.value)
        assertEquals(cipher.encrypt("hunter2"), providerDao.rows.value.single().passwordCipher)
        assertEquals("Eweka NL", state().providers.single().name)

        val bad = post("/api/usenet/providers", """{"name":"X","host":"news.x.com","pipelineDepth":17}""")
        assertEquals(400, bad.status.value)
        assertEquals("Pipeline depth must be between 1 and 16", message(bad).message)
    }

    @Test
    fun `providers and indexers can be toggled, moved and removed`() = runTest {
        post("/api/usenet/providers", """{"name":"A","host":"a.example"}""")
        post("/api/usenet/providers", """{"name":"B","host":"b.example"}""")
        val (a, b) = state().providers.map { it.id }
        post("/api/usenet/providers/move", """{"id":$b,"offset":-1}""")
        post("/api/usenet/providers/enabled", """{"id":$a,"enabled":false}""")
        assertEquals(listOf("B" to true, "A" to false), state().providers.map { it.name to it.enabled })
        post("/api/usenet/providers/remove", """{"id":$b}""")
        assertEquals(listOf("A"), state().providers.map { it.name })

        post("/api/usenet/indexers", """{"name":"NZBgeek","baseUrl":"https://api.nzbgeek.info/api","apiKey":"secretkey"}""")
        val indexerText = stateText()
        assertFalse(indexerText.contains("secretkey"))
        val indexer = state().indexers.single()
        assertEquals("https://api.nzbgeek.info" to "/api", indexer.baseUrl to indexer.apiPath)
        assertTrue(indexer.hasApiKey)
        post("/api/usenet/indexers/remove", """{"id":${indexer.id}}""")
        assertTrue(state().indexers.isEmpty())
    }

    @Test
    fun `several providers are added, edited by id and removed without touching each other`() = runTest {
        assertEquals("Provider added", message(post("/api/usenet/providers", """{"name":"Newshosting","host":"news.newshosting.com","username":"nh","password":"one"}""")).message)
        assertEquals("Provider added", message(post("/api/usenet/providers", """{"id":null,"name":"Eweka","host":"news.eweka.nl","username":"ew","password":"two","priority":1}""")).message)
        val (newshosting, eweka) = state().providers.map { it.id }
        assertTrue(newshosting != eweka)

        val edited = post("/api/usenet/providers", """{"id":$eweka,"name":"Eweka NL","host":"news.eweka.nl","username":"ew2","password":"","priority":1}""")
        assertEquals("Provider saved", message(edited).message)
        assertEquals(
            listOf("Newshosting" to "nh", "Eweka NL" to "ew2"),
            state().providers.map { it.name to it.username },
        )
        val rows = providerDao.rows.value.associateBy { it.id }
        assertEquals(cipher.encrypt("one"), rows.getValue(newshosting).passwordCipher)
        assertEquals(cipher.encrypt("two"), rows.getValue(eweka).passwordCipher, "a blank password keeps the stored one")

        post("/api/usenet/providers/enabled", """{"id":$newshosting,"enabled":false}""")
        post("/api/usenet/providers/move", """{"id":$eweka,"offset":-1}""")
        assertEquals(listOf("Eweka NL" to true, "Newshosting" to false), state().providers.map { it.name to it.enabled })

        assertEquals("Provider removed", message(post("/api/usenet/providers/remove", """{"id":$eweka}""")).message)
        // A form still holding the removed row's id must not resurrect it or overwrite another provider.
        val stale = post("/api/usenet/providers", """{"id":$eweka,"name":"Eweka NL","host":"news.eweka.nl","password":"two"}""")
        assertEquals(400, stale.status.value)
        assertEquals("Provider not found", message(stale).message)
        assertEquals(listOf("Newshosting" to "nh"), state().providers.map { it.name to it.username })

        assertEquals("Provider added", message(post("/api/usenet/providers", """{"name":"Eweka","host":"news.eweka.nl","password":"three"}""")).message)
        assertEquals(2, state().providers.size)
    }

    @Test
    fun `the portal script resets the provider form on remove, on Add and when a preset is picked`() = runTest {
        val page = browser.get("$base/").bodyAsText()
        assertTrue(page.contains("async function removeSource("), "remove resets the form")
        assertTrue(page.contains("onclick: () => openForm('providers', null) }, '+ Add provider')"), "an Add provider action")
        assertTrue(page.contains("editProvider(null);\n    providerPreset.value = p.name;"), "a preset starts a new provider")
        assertTrue(page.contains("if (provider === undefined) editProvider(null);"), "a row removed elsewhere resets the form")
    }

    @Test
    fun `presets, header choices and performance are served and saved`() = runTest {
        val initial = state()
        assertEquals(29, initial.providerPresets.size)
        assertEquals(30, initial.indexerPresets.size)
        assertEquals("PROWLARR" to "SABNZBD", initial.searchHeaders to initial.downloadHeaders)
        assertTrue(initial.headerPresets.any { it.label == "Prowlarr 2.7.0.5723" })
        assertEquals(UsenetProfile.CONSERVATIVE, initial.performance.profile)
        assertTrue(stateText().contains("\"readAheadSegments\":16"), "defaults are serialised")

        post("/api/usenet/headers", """{"search":"NZBHYDRA2","download":"NZBGET"}""")
        assertEquals(HeaderPreset.NZBHYDRA2, store.headers.first().search)
        assertEquals(400, post("/api/usenet/headers", """{"search":"NOPE","download":"NZBGET"}""").status.value)

        post("/api/usenet/performance", """{"profile":"HIGH"}""")
        assertEquals(32, store.performance.first().readAheadSegments)
        val values = json.encodeToString(com.seastreams.tv.domain.usenet.UsenetPerformance.serializer(), store.performance.first().copy(readAheadSegments = 100))
        post("/api/usenet/performance", """{"values":$values}""")
        assertEquals(UsenetProfile.CUSTOM to 100, store.performance.first().let { it.profile to it.readAheadSegments })
    }

    @Test
    fun `Update now reports whether GitHub answered`() = runTest {
        coEvery { updater.updateNow() } returnsMany listOf(true, false)
        assertEquals("Header versions updated", message(post("/api/usenet/headers/update", "{}")).message)
        assertEquals("Couldn't reach GitHub, so the current versions are kept", message(post("/api/usenet/headers/update", "{}")).message)
    }

    @Test
    fun `mutations still require JSON and a trusted host`() = runTest {
        val form = browser.post("$base/api/usenet/providers") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("name=x")
        }
        assertEquals(415, form.status.value)
        val port = base.substringAfterLast(':')
        val statusLine = Socket("127.0.0.1", port.toInt()).use { socket ->
            socket.getOutputStream().write("GET /api/usenet HTTP/1.1\r\nHost: evil.example:$port\r\nConnection: close\r\n\r\n".toByteArray())
            socket.getInputStream().bufferedReader().readLine()
        }
        assertTrue(statusLine.contains("403"), statusLine)
    }

    @Test
    fun `the provider Test signs in with the stored or typed password and reports failures as errors`() = runTest {
        val news = FakeNntpServer()
        try {
            val form = """{"name":"Fake","host":"127.0.0.1","port":${news.port},"tls":false,"username":"user","password":"pass","maxConnections":2}"""
            post("/api/usenet/providers", form)
            val id = state().providers.single().id
            val savedBody = form.replace("\"password\":\"pass\"", "\"id\":$id")
            val saved = message(post("/api/usenet/providers/test", savedBody))
            assertTrue(saved.ok, saved.message + " for " + savedBody)
            assertTrue(saved.message.startsWith("Fake: connected and signed in"))
            val typed = post("/api/usenet/providers/test", form.replace("\"pass\"", "\"wrong\""))
            assertEquals(400, typed.status.value)
            assertEquals("Fake rejected the username or password. Server said: 481 Authentication rejected", message(typed).message)
            news.sessionLimit = 0
            assertTrue(message(post("/api/usenet/providers/test", form)).message.contains("connection limit"))
        } finally {
            news.close()
        }
    }
}
