package com.seastreams.tv.core.webserver

import com.seastreams.tv.core.datastore.DebridSettingsStore
import com.seastreams.tv.core.datastore.UsenetSettingsStore
import com.seastreams.tv.data.debrid.DebridClientIds
import com.seastreams.tv.data.debrid.DebridConnectionTester
import com.seastreams.tv.data.debrid.DebridServiceKind
import com.seastreams.tv.data.debrid.DebridSourcesRepository
import com.seastreams.tv.data.debrid.DebridTestResult
import com.seastreams.tv.data.debrid.auth.DeviceAuthSessions
import com.seastreams.tv.data.debrid.auth.DeviceFlowProvider
import com.seastreams.tv.data.debrid.auth.DevicePoll
import com.seastreams.tv.data.debrid.auth.DeviceTokens
import com.seastreams.tv.data.usenet.UsenetSourcesRepository
import com.seastreams.tv.testutil.FakeDebridProviderDao
import com.seastreams.tv.testutil.FakeNewznabIndexerDao
import com.seastreams.tv.testutil.FakeSecretCipher
import com.seastreams.tv.testutil.FakeUsenetProviderDao
import com.seastreams.tv.testutil.InMemoryPreferences
import com.seastreams.tv.testutil.ScriptedDeviceFlow
import com.seastreams.tv.usenet.nntp.NntpConnectionTester
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.Socket
import java.time.Clock

/** Drives the Debrid endpoints of the real Ktor CIO portal over HTTP, as the phone's browser does. */
class PortalDebridRoutesTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val cipher = FakeSecretCipher()
    private val prefs = InMemoryPreferences()
    private val usenetStore = UsenetSettingsStore(prefs)
    private val debridStore = DebridSettingsStore(prefs)
    private val dao = FakeDebridProviderDao()
    private val repository = DebridSourcesRepository(dao, cipher, Dispatchers.IO)
    private val tester = mockk<DebridConnectionTester>()
    private val torBox = ScriptedDeviceFlow(
        DebridServiceKind.TORBOX,
        ArrayDeque(listOf(DevicePoll.Pending, DevicePoll.Linked(DeviceTokens("tb-device-token")))),
        intervalSeconds = 1,
    )
    private val allDebrid = ScriptedDeviceFlow(DebridServiceKind.ALL_DEBRID)
    private lateinit var scope: CoroutineScope
    private lateinit var portal: LocalPortalServer
    private lateinit var browser: HttpClient
    private lateinit var base: String

    @BeforeEach
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val flows = DeviceFlowProvider { kind -> listOf(torBox, allDebrid).firstOrNull { it.kind == kind } }
        val sessions = DeviceAuthSessions(flows, repository, scope, Clock.systemUTC())
        val usenet = PortalUsenetController(
            UsenetSourcesRepository(FakeUsenetProviderDao(), FakeNewznabIndexerDao(), cipher, Dispatchers.IO),
            usenetStore, mockk(), NntpConnectionTester(Dispatchers.IO), mockk(),
        )
        val debrid = PortalDebridController(repository, debridStore, usenetStore, tester, sessions, DebridClientIds(), Clock.systemUTC())
        portal = LocalPortalServer(mockk<PortalController>(relaxed = true), usenet, debrid = debrid)
        base = "http://127.0.0.1:" + requireNotNull(portal.start(port = 0, host = "127.0.0.1"))
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

    private suspend fun stateText(): String = browser.get("$base/api/debrid").bodyAsText()

    private suspend fun state(): DebridPortalState = json.decodeFromString(stateText())

    @Test
    fun `a pasted key is added, listed without the key, and edits with a blank key keep it`() = runBlocking<Unit> {
        val added = post("/api/debrid/providers", """{"kind":"TORBOX","apiKey":"tb-secret-key-123"}""")
        assertEquals(200 to "TorBox added", added.status.value to message(added).message)

        val text = stateText()
        assertFalse(text.contains("tb-secret-key-123"), "key leaked to the browser")
        assertFalse(text.contains(cipher.encrypt("tb-secret-key-123")), "ciphertext leaked to the browser")
        val provider = state().providers.single()
        assertEquals(listOf("TORBOX", "TorBox", "API_KEY", "API key"), listOf(provider.kind, provider.name, provider.credentialState, provider.credentialLabel))
        assertTrue(provider.hasCredential)

        assertEquals(200, post("/api/debrid/providers", """{"id":${provider.id},"kind":"TORBOX","apiKey":"","name":"My TorBox"}""").status.value)
        assertEquals(cipher.encrypt("tb-secret-key-123"), dao.rows.value.single().credentialCipher)
        assertEquals("My TorBox", state().providers.single().name)

        val duplicate = post("/api/debrid/providers", """{"kind":"TORBOX","apiKey":"tb-other-key-123"}""")
        assertEquals(400 to "TorBox is already added. Edit it instead", duplicate.status.value to message(duplicate).message)
        val injected = post("/api/debrid/providers", """{"kind":"PREMIUMIZE","apiKey":"pm-key\r\nX-Evil: 1"}""")
        assertEquals(400 to "Enter a valid Premiumize API key", injected.status.value to message(injected).message)
        assertEquals(400, post("/api/debrid/providers", """{"kind":"NOPE","apiKey":"abcdefghijk"}""").status.value)
    }

    @Test
    fun `providers can be toggled, moved, tested and removed`() = runBlocking<Unit> {
        post("/api/debrid/providers", """{"kind":"REAL_DEBRID","apiKey":"rd-key-0123456789"}""")
        post("/api/debrid/providers", """{"kind":"PREMIUMIZE","apiKey":"pm-key-0123456789"}""")
        val (rd, pm) = state().providers.map { it.id }

        assertEquals("Provider disabled", message(post("/api/debrid/providers/enabled", """{"id":$rd,"enabled":false}""")).message)
        post("/api/debrid/providers/move", """{"id":$pm,"offset":-1}""")
        assertEquals(listOf(pm to true, rd to false), state().providers.map { it.id to it.enabled })

        coEvery { tester.test(pm) } returns (DebridServiceKind.PREMIUMIZE to DebridTestResult.Success(null))
        coEvery { tester.testKey(DebridServiceKind.REAL_DEBRID, "typed-key-0123") } returns DebridTestResult.BadCredentials
        assertEquals(200 to "Connected", post("/api/debrid/providers/test", """{"id":$pm}""").let { it.status.value to message(it).message })
        val failed = post("/api/debrid/providers/test", """{"id":$rd,"kind":"REAL_DEBRID","apiKey":"typed-key-0123"}""")
        assertEquals(400 to "Invalid API key or token", failed.status.value to message(failed).message)

        assertEquals("Provider removed", message(post("/api/debrid/providers/remove", """{"id":$pm}""")).message)
        assertEquals(listOf(rd), state().providers.map { it.id })
    }

    @Test
    fun `the source toggles switch only what they name`() = runBlocking<Unit> {
        assertFalse(state().enabled)
        assertEquals(200, post("/api/sources/toggles", """{"usenet":true}""").status.value)
        assertTrue(usenetStore.enabled.first())
        assertFalse(debridStore.enabled.first())
        assertTrue(json.decodeFromString<UsenetPortalState>(browser.get("$base/api/usenet").bodyAsText()).enabled)

        post("/api/sources/toggles", """{"debrid":true}""")
        assertTrue(state().enabled)
        post("/api/sources/toggles", """{"usenet":false}""")
        assertFalse(usenetStore.enabled.first())
        assertTrue(debridStore.enabled.first())
        assertEquals(400, post("/api/sources/toggles", "{}").status.value)
    }

    @Test
    fun `a code sign-in starts, reports pending, links and adds the provider`() = runBlocking<Unit> {
        val started = post("/api/debrid/auth/start", """{"kind":"TORBOX"}""")
        val body = started.bodyAsText()
        assertEquals(200, started.status.value)
        assertFalse(body.contains("device-secret"), "the device code never leaves the TV")
        val session = json.decodeFromString<DebridAuthSessionDto>(body)
        assertTrue(session.session.matches(Regex("[0-9a-f]{32}")))
        assertEquals(listOf("TORBOX", "CODE1", "https://example.com/device", "pending"), listOf(session.kind, session.userCode, session.verificationUrl, session.status))
        assertTrue(session.expiresInSeconds in 590..600)

        var status = session.status
        repeat(50) {
            if (status != "pending") return@repeat
            delay(100)
            status = json.decodeFromString<DebridAuthSessionDto>(browser.get("$base/api/debrid/auth/${session.session}").bodyAsText()).status
        }
        assertEquals("linked", status)
        val provider = state().providers.single()
        assertEquals("SIGNED_IN" to "DEVICE", provider.credentialState to provider.authMethod)
        assertFalse(stateText().contains("tb-device-token"))
    }

    @Test
    fun `a sign-in can be cancelled, unknown sessions end, and key-only services refuse`() = runBlocking<Unit> {
        val session = json.decodeFromString<DebridAuthSessionDto>(post("/api/debrid/auth/start", """{"kind":"ALL_DEBRID"}""").bodyAsText())
        assertEquals("Sign-in cancelled", message(post("/api/debrid/auth/cancel", """{"session":"${session.session}"}""")).message)
        assertEquals("cancelled", json.decodeFromString<DebridAuthSessionDto>(browser.get("$base/api/debrid/auth/${session.session}").bodyAsText()).status)

        assertEquals(404, browser.get("$base/api/debrid/auth/" + "0".repeat(32)).status.value)
        assertEquals(404, browser.get("$base/api/debrid/auth/not-a-session").status.value)
        val refused = post("/api/debrid/auth/start", """{"kind":"PREMIUMIZE"}""")
        assertEquals(400 to "Premiumize has no code sign-in here. Paste your API key instead", refused.status.value to message(refused).message)
    }

    @Test
    fun `services list their key page and only the available code sign-ins`() = runBlocking<Unit> {
        val services = state().services.associateBy { it.kind }
        assertEquals(DebridServiceKind.entries.map { it.name }, services.keys.toList())
        assertEquals("device code", services.getValue("REAL_DEBRID").deviceSignIn)
        assertEquals("PIN code", services.getValue("ALL_DEBRID").deviceSignIn)
        assertEquals("device code", services.getValue("TORBOX").deviceSignIn)
        listOf("PREMIUMIZE", "DEBRID_LINK", "OFFCLOUD", "PUT_IO").forEach { assertEquals(null, services.getValue(it).deviceSignIn, it) }
        assertTrue(services.values.all { it.keyPageUrl.startsWith("https://") })
    }

    @Test
    fun `mutations require JSON and every route a trusted host`() = runBlocking<Unit> {
        val form = browser.post("$base/api/debrid/providers") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("kind=TORBOX&apiKey=abcdefghijk")
        }
        assertEquals(415, form.status.value)
        val start = browser.post("$base/api/debrid/auth/start") {
            contentType(ContentType.Text.Plain)
            setBody("""{"kind":"TORBOX"}""")
        }
        assertEquals(415, start.status.value)
        assertEquals(0, torBox.starts)
        val port = base.substringAfterLast(':').toInt()
        listOf("/api/debrid", "/api/debrid/auth/" + "a".repeat(32)).forEach { path ->
            val statusLine = Socket("127.0.0.1", port).use { socket ->
                socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: evil.example:$port\r\nConnection: close\r\n\r\n".toByteArray())
                socket.getInputStream().bufferedReader().readLine()
            }
            assertTrue(statusLine.contains("403"), "$path: $statusLine")
        }
    }
}
