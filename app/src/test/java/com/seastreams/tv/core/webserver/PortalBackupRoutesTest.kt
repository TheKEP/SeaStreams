package com.seastreams.tv.core.webserver

import com.seastreams.tv.data.backup.AppBackupCodec
import com.seastreams.tv.data.backup.BackupScope
import com.seastreams.tv.data.backup.RestoreSummary
import com.seastreams.tv.testutil.BackupRig
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Drives `/api/backup/…` on the real Ktor CIO portal over HTTP, as the phone's browser does, against an in-memory TV. */
class PortalBackupRoutesTest {

    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var tv: BackupRig
    private lateinit var portal: LocalPortalServer
    private lateinit var browser: HttpClient
    private lateinit var base: String
    private var kenId = 0L

    @BeforeEach
    fun setUp() {
        tv = BackupRig()
        runBlocking {
            kenId = tv.addProfile("Ken's TV").id
            tv.addProfile("Kids", kids = true)
            tv.configureUsenet()
        }
        portal = LocalPortalServer(mockk(relaxed = true), mockk(relaxed = true), backup = PortalBackupController(tv.exporter, tv.restorer))
        val port = requireNotNull(portal.start(port = 0, host = "127.0.0.1"))
        base = "http://127.0.0.1:$port"
        browser = HttpClient(OkHttp)
    }

    @AfterEach
    fun tearDown() {
        portal.stop()
        browser.close()
    }

    private suspend fun restore(body: String, profileId: Long?, type: ContentType = ContentType.Application.Json): HttpResponse =
        browser.post("$base/api/backup/restore?profileId=${profileId ?: ""}") {
            contentType(type)
            setBody(body)
        }

    @Test
    fun `a profile export downloads as an attachment named after the profile, never cached`() = runTest {
        val response = browser.get("$base/api/backup/export?scope=profile&profileId=$kenId&includeCredentials=false")

        assertEquals(200, response.status.value)
        assertEquals("attachment; filename=\"seastreams-profile-ken-s-tv.json\"", response.headers[HttpHeaders.ContentDisposition])
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        val backup = AppBackupCodec.decode(response.bodyAsText())
        assertEquals("profile", backup.scope)
        assertEquals(listOf("Ken's TV"), backup.profiles.map { it.profile.name })
    }

    @Test
    fun `a full export holds every profile, and credentials only when asked for`() = runTest {
        val plain = browser.get("$base/api/backup/export?scope=app&includeCredentials=false")
        assertEquals("attachment; filename=\"seastreams-full-backup.json\"", plain.headers[HttpHeaders.ContentDisposition])
        val plainText = plain.bodyAsText()
        assertEquals(2, AppBackupCodec.decode(plainText).profiles.size)
        assertFalse(plainText.contains("nntp-secret") || plainText.contains(BackupRig.TMDB_KEY))

        val withSecrets = browser.get("$base/api/backup/export?scope=app&includeCredentials=true").bodyAsText()
        assertTrue(withSecrets.contains("nntp-secret") && withSecrets.contains("indexer-key") && withSecrets.contains(BackupRig.TMDB_KEY))
    }

    @Test
    fun `an export without a scope or for a missing profile is a 400 with a message`() = runTest {
        val noScope = browser.get("$base/api/backup/export?profileId=$kenId")
        assertEquals(400, noScope.status.value)
        assertEquals("Choose what to back up", json.decodeFromString<ApiMessage>(noScope.bodyAsText()).message)
        val gone = browser.get("$base/api/backup/export?scope=profile&profileId=99")
        assertEquals("That profile no longer exists", json.decodeFromString<ApiMessage>(gone.bodyAsText()).message)
    }

    @Test
    fun `a restore answers with what it restored`() = runTest {
        val file = tv.exporter.export(BackupScope.APP, null, includeCredentials = false).json

        val response = restore(file, profileId = null)

        assertEquals(200, response.status.value)
        val answer = json.decodeFromString<BackupRestoreResponse>(response.bodyAsText())
        assertTrue(answer.ok)
        assertEquals(RestoreSummary("app", 2, 0, 1, 1, 0, 0), answer.summary)
        assertEquals(
            "Restored 2 profiles, with 1 Usenet provider, 1 indexer. The file had no credentials: passwords, keys and tokens already on the TV were kept.",
            answer.message,
        )
    }

    @Test
    fun `an invalid file is a 400 with the validation message and changes nothing`() = runTest {
        val before = tv.addonDao.rows.value
        val response = restore("""{"version":3,"scope":"app","profiles":[]}""", kenId)

        assertEquals(400, response.status.value)
        assertEquals(ApiMessage(false, "Unsupported backup file version 3"), json.decodeFromString<ApiMessage>(response.bodyAsText()))
        assertEquals(before, tv.addonDao.rows.value)
    }

    @Test
    fun `restore needs JSON, a trusted host and a file under the size limit`() = runTest {
        val file = tv.exporter.export(BackupScope.PROFILE, kenId, includeCredentials = false).json
        assertEquals(415, restore(file, kenId, ContentType.Text.Plain).status.value)
        assertEquals(413, restore("{\"pad\":\"${"x".repeat(MAX_BACKUP_CHARS)}\"}", kenId).status.value)

        val port = base.substringAfterLast(':')
        fun rawStatus(request: String): String = java.net.Socket("127.0.0.1", port.toInt()).use { socket ->
            socket.getOutputStream().write(request.toByteArray())
            socket.getInputStream().bufferedReader().readLine()
        }
        val export = rawStatus("GET /api/backup/export?scope=app HTTP/1.1\r\nHost: attacker.example:$port\r\nConnection: close\r\n\r\n")
        assertTrue(export.contains("403"), export)
        val bytes = file.toByteArray()
        val post = rawStatus(
            "POST /api/backup/restore?profileId=$kenId HTTP/1.1\r\nHost: attacker.example:$port\r\nContent-Type: application/json\r\n" +
                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n" + file,
        )
        assertTrue(post.contains("403"), post)
        assertEquals(0, tv.transactions)
        assertEquals(listOf("Ken's TV", "Kids"), tv.profileDao.observeAll().first().map { it.name })
    }
}
