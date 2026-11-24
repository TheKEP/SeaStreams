package com.seastreams.tv.core.webserver

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.seastreams.tv.core.database.entity.ProfileEntity
import com.seastreams.tv.core.datastore.StreamProcessingBackupCodec
import com.seastreams.tv.core.datastore.StreamProcessingStore
import com.seastreams.tv.data.profile.ProfileRepository
import com.seastreams.tv.domain.streams.FormatterId
import com.seastreams.tv.domain.streams.SortCriterion
import com.seastreams.tv.domain.streams.SortKey
import com.seastreams.tv.domain.streams.StreamFormatterChoice
import com.seastreams.tv.domain.streams.StreamSort
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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
import java.time.Clock

/** Drives the stream filter / formatter endpoints of the real Ktor CIO portal over HTTP, as the phone's browser does. */
class PortalStreamRoutesTest {

    @TempDir
    lateinit var dir: File

    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var scope: CoroutineScope
    private lateinit var store: StreamProcessingStore
    private lateinit var portal: LocalPortalServer
    private lateinit var browser: HttpClient
    private lateinit var base: String

    @BeforeEach
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        store = StreamProcessingStore(PreferenceDataStoreFactory.create(scope = scope) { File(dir, "s.preferences_pb") })
        val profiles = mockk<ProfileRepository> {
            every { observeProfiles() } returns flowOf(listOf(profile(1, "Ken"), profile(2, "Kids")))
        }
        portal = LocalPortalServer(mockk(relaxed = true), mockk(relaxed = true), streams = PortalStreamController(profiles, store, Clock.systemUTC()))
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

    private suspend fun state(profileId: Long): StreamPortalState = json.decodeFromString(browser.get("$base/api/streams?profileId=$profileId").bodyAsText())

    @Test
    fun `format-preview renders the aio-formatter template`() = runTest {
        val custom = state(1).presets.getValue(FormatterId.CUSTOM.name)
        val response = post("/api/streams/format-preview", json.encodeToString(FormatPreviewRequest.serializer(), FormatPreviewRequest(custom.name, custom.description)))
        assertEquals(200, response.status.value)
        val preview = json.decodeFromString<FormatPreviewResponse>(response.bodyAsText())
        assertEquals("🔥 4K 🎬 Movie Title (2023)", preview.name)
        assertEquals("🎧 Atmos | TrueHD 🔊 7.1 📦 63 GB ⏱️ 2h:32m:0s 📅 10d", preview.description.lines()[2])
    }

    @Test
    fun `filters typed on the phone are saved for one profile only`() = runTest {
        val filters = state(1).filters.copy(
            keywords = state(1).filters.keywords.copy(excluded = listOf("CAM", "HDTS")),
            regex = state(1).filters.regex.copy(excluded = listOf("/hdcam/in")),
        )
        val body = json.encodeToString(StreamFiltersRequest.serializer(), StreamFiltersRequest(1, filters))
        val saved = post("/api/streams/filters", body)
        assertEquals(200, saved.status.value, saved.bodyAsText())

        assertEquals(listOf("CAM", "HDTS"), store.settings(1).first().filters.keywords.excluded)
        assertEquals(listOf("/hdcam/in"), state(1).filters.regex.excluded)
        assertEquals(emptyList<String>(), state(2).filters.keywords.excluded)
    }

    @Test
    fun `an invalid regex or unknown profile is refused with a readable message`() = runTest {
        val filters = state(1).filters.copy(regex = state(1).filters.regex.copy(required = listOf("/([oops/")))
        val bad = post("/api/streams/filters", json.encodeToString(StreamFiltersRequest.serializer(), StreamFiltersRequest(1, filters)))
        assertEquals(400, bad.status.value)
        assertTrue(json.decodeFromString<ApiMessage>(bad.bodyAsText()).message.startsWith("Not a valid regex"))

        val missing = post("/api/streams/formatter", """{"profileId":9,"formatter":{"id":"PRISM"}}""")
        assertEquals(400, missing.status.value)
        assertEquals(404, browser.get("$base/api/streams?profileId=9").status.value)
    }

    @Test
    fun `format-preview refuses an oversized body before reading it, and a malformed one`() = runTest {
        val huge = post("/api/streams/format-preview", """{"name":"${"x".repeat(MAX_BODY_CHARS)}","description":""}""")
        assertEquals(413, huge.status.value)

        val malformed = post("/api/streams/format-preview", """{"name":1""")
        assertEquals(400, malformed.status.value)
        assertEquals("Malformed request", json.decodeFromString<ApiMessage>(malformed.bodyAsText()).message)
    }

    @Test
    fun `custom templates are saved with the formatter choice`() = runTest {
        val ok = post("/api/streams/formatter", """{"profileId":2,"formatter":{"id":"CUSTOM","customName":"{addon.name}","customDescription":"{stream.filename}"}}""")
        assertEquals(200, ok.status.value)
        val formatter = store.settings(2).first().formatter
        assertEquals(FormatterId.CUSTOM, formatter.id)
        assertEquals("{addon.name}", formatter.customName)
        assertEquals(FormatterId.ORIGINAL, store.settings(1).first().formatter.id)
    }

    @Test
    fun `GET exposes the master toggles, on by default`() = runTest {
        val st = state(1)
        assertTrue(st.filteringEnabled && st.sortingEnabled && st.formattingEnabled)
        store.setSortingEnabled(1, false)
        assertFalse(state(1).sortingEnabled)
        assertTrue(state(2).sortingEnabled)
    }

    @Test
    fun `a checkbox posts only its flag and the saved rules stay`() = runTest {
        store.updateFilters(1) { it.copy(keywords = it.keywords.copy(excluded = listOf("CAM"))) }
        store.updateFormatter(1) { StreamFormatterChoice(FormatterId.CUSTOM, "{addon.name}", null) }

        assertEquals(200, post("/api/streams/filters", """{"profileId":1,"filteringEnabled":false}""").status.value)
        assertEquals(200, post("/api/streams/sort", """{"profileId":1,"sortingEnabled":false}""").status.value)
        val formatter = post("/api/streams/formatter", """{"profileId":1,"formattingEnabled":false}""")
        assertEquals("Stream formatting turned off for Ken", json.decodeFromString<ApiMessage>(formatter.bodyAsText()).message)

        val off = state(1)
        assertFalse(off.filteringEnabled || off.sortingEnabled || off.formattingEnabled)
        assertEquals(listOf("CAM"), off.filters.keywords.excluded)
        assertEquals("{addon.name}", off.formatter.customName)
        val two = state(2)
        assertTrue(two.filteringEnabled && two.sortingEnabled && two.formattingEnabled)

        post("/api/streams/filters", """{"profileId":1,"filteringEnabled":true}""")
        assertTrue(state(1).filteringEnabled)
        assertEquals(listOf("CAM"), state(1).filters.keywords.excluded)
    }

    @Test
    fun `a save carries rules and flag together, and omitting the flag keeps it`() = runTest {
        val sort = StreamSort(global = listOf(SortCriterion(SortKey.SEEDERS)))
        val body = json.encodeToString(StreamSortRequest.serializer(), StreamSortRequest(2, sort, sortingEnabled = false))
        assertEquals(200, post("/api/streams/sort", body).status.value)
        assertEquals(sort, state(2).sort)
        assertFalse(state(2).sortingEnabled)

        val rulesOnly = json.encodeToString(StreamSortRequest.serializer(), StreamSortRequest(2, StreamSort()))
        assertEquals(200, post("/api/streams/sort", rulesOnly).status.value)
        assertEquals(StreamSort(), state(2).sort)
        assertFalse(state(2).sortingEnabled)
    }

    @Test
    fun `a save with neither rules nor flag is refused`() = runTest {
        val empty = post("/api/streams/filters", """{"profileId":1}""")
        assertEquals(400, empty.status.value)
        assertEquals("Nothing to save", json.decodeFromString<ApiMessage>(empty.bodyAsText()).message)
        assertEquals(400, post("/api/streams/formatter", """{"profileId":9,"formattingEnabled":false}""").status.value)
    }

    @Test
    fun `export downloads the profile's stages as a named JSON attachment`() = runTest {
        store.updateFilters(1, enabled = false) { it.copy(keywords = it.keywords.copy(excluded = listOf("CAM"))) }
        val response = browser.get("$base/api/streams/export?profileId=1")
        assertEquals(200, response.status.value)
        assertEquals("attachment; filename=\"seastreams-stream-settings-ken.json\"", response.headers["Content-Disposition"])
        assertTrue(response.headers["Content-Type"].orEmpty().startsWith("application/json"))
        val backup = StreamProcessingBackupCodec.decode(response.bodyAsText())
        assertFalse(backup.filters.enabled)
        assertEquals(listOf("CAM"), backup.filters.rules.keywords.excluded)

        assertEquals(404, browser.get("$base/api/streams/export?profileId=9").status.value)
        assertEquals(400, browser.get("$base/api/streams/export").status.value)
    }

    @Test
    fun `import writes an exported file onto another profile, master toggles included`() = runTest {
        store.updateSort(1, enabled = false) { it.copy(global = listOf(SortCriterion(SortKey.SEEDERS))) }
        store.updateFormatter(1) { StreamFormatterChoice(FormatterId.CUSTOM, "{addon.name}   {stream.resolution}", null) }
        val file = browser.get("$base/api/streams/export?profileId=1").bodyAsText()

        val imported = post("/api/streams/import?profileId=2", file)
        assertEquals(200, imported.status.value, imported.bodyAsText())
        assertEquals("Stream settings imported for Kids", json.decodeFromString<ApiMessage>(imported.bodyAsText()).message)
        assertEquals(store.settings(1).first(), store.settings(2).first())
        assertFalse(state(2).sortingEnabled)
        assertEquals("{addon.name}   {stream.resolution}", state(2).formatter.customName)
    }

    @Test
    fun `import refuses bad JSON, a bad regex, the wrong content type and unknown profiles`() = runTest {
        val before = store.settings(1).first()
        val garbage = post("/api/streams/import?profileId=1", "{nope")
        assertEquals(400, garbage.status.value)
        assertEquals("That isn't a seastreams stream settings file", json.decodeFromString<ApiMessage>(garbage.bodyAsText()).message)

        val regex = """{"version":1,"filters":{"rules":{"regex":{"required":["/([oops/"]}}},"sort":{"rules":{}},"formatter":{"rules":{}}}"""
        val badRegex = post("/api/streams/import?profileId=1", regex)
        assertEquals(400, badRegex.status.value)
        assertTrue(json.decodeFromString<ApiMessage>(badRegex.bodyAsText()).message.startsWith("Not a valid regex"))

        val text = browser.post("$base/api/streams/import?profileId=1") {
            contentType(ContentType.Text.Plain)
            setBody(browser.get("$base/api/streams/export?profileId=1").bodyAsText())
        }
        assertEquals(415, text.status.value)
        val valid = browser.get("$base/api/streams/export?profileId=1").bodyAsText()
        assertEquals(400, post("/api/streams/import?profileId=9", valid).status.value)
        assertEquals(400, post("/api/streams/import", valid).status.value)
        assertEquals(before, store.settings(1).first())
    }

    @Test
    fun `the preview answers spaces exactly as typed, and the page shows them`() = runTest {
        val body = json.encodeToString(FormatPreviewRequest.serializer(), FormatPreviewRequest("  {addon.name}   4K", "a  {?{addon.name} ?}b"))
        val preview = json.decodeFromString<FormatPreviewResponse>(post("/api/streams/format-preview", body).bodyAsText())
        assertEquals("  Torrentio   4K", preview.name)
        assertEquals("a  Torrentio b", preview.description)
        // pre-line would collapse those runs of spaces in the browser.
        assertTrue(PortalHtmlTemplate.render().contains(".pv-name { font-weight: 700; white-space: pre-wrap;"))
    }

    @Test
    fun `the served portal page carries the backup buttons and a script that tokenises cleanly`() = runTest {
        val page = browser.get("$base/")
        assertEquals(200, page.status.value)
        val html = page.bodyAsText()
        listOf("sp-export", "sp-import", "sp-import-file", "/api/streams/export?profileId=", "/api/streams/import?profileId=")
            .forEach { assertTrue(html.contains(it), it) }
        val script = html.substringAfter("<script>").substringBefore("</script>")
        val errors = JsSyntaxChecker.check(script)
        assertTrue(errors.isEmpty(), errors.joinToString("\n"))
    }

    @Test
    fun `export and import refuse a foreign Host header and leave the settings alone`() = runTest {
        store.updateSort(1) { it.copy(global = listOf(SortCriterion(SortKey.SIZE))) }
        val before = store.settings(1).first()
        val port = base.substringAfterLast(':')
        val body = browser.get("$base/api/streams/export?profileId=1").bodyAsText()
        fun rawStatus(request: String): String = java.net.Socket("127.0.0.1", port.toInt()).use { socket ->
            socket.getOutputStream().write(request.toByteArray())
            socket.getInputStream().bufferedReader().readLine()
        }
        val export = rawStatus("GET /api/streams/export?profileId=1 HTTP/1.1\r\nHost: attacker.example:$port\r\nConnection: close\r\n\r\n")
        assertTrue(export.contains("403"), export)
        val bytes = body.toByteArray()
        val import = rawStatus(
            "POST /api/streams/import?profileId=2 HTTP/1.1\r\nHost: attacker.example:$port\r\nContent-Type: application/json\r\n" +
                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n" + body,
        )
        assertTrue(import.contains("403"), import)
        assertEquals(before, store.settings(1).first())
        assertEquals(StreamSort(), store.settings(2).first().sort)
    }

    @Test
    fun `an oversized import is refused before it is read`() = runTest {
        val huge = post("/api/streams/import?profileId=1", "{\"version\":1,\"pad\":\"${"x".repeat(MAX_BODY_CHARS)}\"}")
        assertEquals(413, huge.status.value)
    }

    @Test
    fun `backup file names are reduced to a safe slug`() {
        assertEquals("seastreams-stream-settings-ken-s-tv.json", PortalStreamController.backupFileName("Ken's TV"))
        assertEquals("seastreams-stream-settings-profile.json", PortalStreamController.backupFileName("👶"))
    }

    private fun profile(id: Long, name: String) =
        ProfileEntity(id = id, name = name, avatarIndex = 0, isKids = false, movieCeiling = null, tvCeiling = null, allowUnrated = true, createdAtMillis = 0)
}
