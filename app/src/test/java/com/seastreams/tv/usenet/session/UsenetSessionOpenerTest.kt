package com.seastreams.tv.usenet.session

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.seastreams.tv.core.database.entity.UsenetProviderEntity
import com.seastreams.tv.core.datastore.UsenetSettingsStore
import com.seastreams.tv.data.usenet.UsenetSourcesRepository
import com.seastreams.tv.data.usenet.newznab.NzbGrabber
import com.seastreams.tv.data.usenet.newznab.NzbRequest
import com.seastreams.tv.data.usenet.newznab.UsenetHttp
import com.seastreams.tv.domain.usenet.DamagePolicy
import com.seastreams.tv.domain.usenet.UsenetPerformance
import com.seastreams.tv.testutil.FakeNewznabIndexerDao
import com.seastreams.tv.testutil.FakeNntpServer
import com.seastreams.tv.testutil.FakeSecretCipher
import com.seastreams.tv.testutil.FakeUsenetProviderDao
import com.seastreams.tv.testutil.RarTestBuilder
import com.seastreams.tv.testutil.TestHttpServer
import com.seastreams.tv.usenet.archive.ArchiveRefusedException
import com.seastreams.tv.usenet.archive.UsenetUnplayableException
import com.seastreams.tv.usenet.archive.readAll
import com.seastreams.tv.usenet.cache.NzbFileCache
import com.seastreams.tv.usenet.nntp.DefaultSocketOpener
import com.seastreams.tv.usenet.nntp.NntpEngine
import com.seastreams.tv.usenet.nntp.monotonicMillis
import com.seastreams.tv.usenet.nzb.NzbFile
import com.seastreams.tv.usenet.verify.ReleaseHealth
import com.seastreams.tv.usenet.verify.ReleaseHealthCache
import io.ktor.http.ContentType
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/** NZB XML for [files] as an indexer would serve it. */
fun nzbXml(files: List<NzbFile>): String = buildString {
    append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE nzb PUBLIC \"-//newzBin//DTD NZB 1.1//EN\" \"http://www.newzbin.com/DTD/nzb/nzb-1.1.dtd\">\n")
    append("<nzb xmlns=\"http://www.newzbin.com/DTD/2003/nzb\">\n")
    for (file in files) {
        append("<file poster=\"p\" date=\"0\" subject=\"").append(file.subject.replace("\"", "&quot;")).append("\">")
        append("<groups><group>alt.binaries.test</group></groups><segments>")
        file.segments.forEach { append("<segment bytes=\"${it.bytes}\" number=\"${it.number}\">${it.messageId}</segment>") }
        append("</segments></file>\n")
    }
    append("</nzb>")
}

class UsenetSessionOpenerTest {
    @TempDir
    lateinit var dir: File

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nntp = FakeNntpServer()
    private val nzbs = ConcurrentHashMap<String, String>()
    private val web = TestHttpServer {
        get("/nzb/{id}") { call.respondText(nzbs[call.parameters["id"]].orEmpty(), ContentType.Application.Xml) }
    }
    private val engine = NntpEngine(DefaultSocketOpener, monotonicMillis, null)
    private val streams = UsenetStreamRegistry()
    private val health = ReleaseHealthCache()
    private val store by lazy { UsenetSettingsStore(PreferenceDataStoreFactory.create(scope = scope) { File(dir, "s.preferences_pb") }) }
    private val payload = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()) + Random(4).nextBytes(40_000)

    private val opener by lazy {
        val cipher = FakeSecretCipher()
        val providers = FakeUsenetProviderDao()
        runBlocking {
            providers.insert(
                UsenetProviderEntity(
                    name = "Fake", host = "127.0.0.1", port = nntp.port, tls = false, username = "user",
                    passwordCipher = cipher.encrypt("pass"), maxConnections = 4, pipelineDepth = 2, priority = 0,
                    isBackup = false, enabled = true, sortOrder = 0,
                ),
            )
        }
        val repository = UsenetSourcesRepository(providers, FakeNewznabIndexerDao(), cipher, Dispatchers.IO)
        val http = UsenetHttp({ OkHttpClient() }, store)
        UsenetSessionOpener(
            streams,
            UsenetEngineSetup(repository, store, { engine }, Dispatchers.IO),
            NzbGrabber(http, NzbFileCache({ dir }), Dispatchers.IO),
            health,
            Dispatchers.IO,
        )
    }

    @AfterEach
    fun tearDown() {
        engine.close()
        nntp.close()
        web.close()
        scope.cancel()
    }

    private fun performance(transform: (UsenetPerformance) -> UsenetPerformance) = runBlocking { store.updatePerformance(transform) }

    /** Publishes [files] as an indexer NZB and returns the stream token. */
    private fun release(id: String, files: List<NzbFile>): String {
        nzbs[id] = nzbXml(files)
        val url = streams.register(UsenetGrab.Indexed(1, "Idx", id, NzbRequest(web.url("/nzb/$id"), "nzb:$id"), "Movie $id", 0))
        return checkNotNull(UsenetStreamRegistry.tokenOf(url))
    }

    private fun open(token: String) = runBlocking { opener.openSuspend(token) }

    @Test
    fun `a plain release opens and reads byte for byte`() {
        val token = release("plain", listOf(nntp.addFile(payload, partSize = 2_000, name = "Movie.2024.mkv", idPrefix = "plain")))
        open(token).use { session ->
            assertEquals("Movie.2024.mkv", session.name)
            assertEquals(payload.size.toLong(), session.size)
            assertArrayEquals(payload, session.bytes())
        }
    }

    @Test
    fun `a stored RAR release plays, a compressed one is refused and remembered`() {
        val volumes = RarTestBuilder.rar4("Movie.mkv", payload, 15_000).mapIndexed { i, bytes ->
            nntp.addFile(bytes, partSize = 2_000, name = "Movie.part0${i + 1}.rar", idPrefix = "rar$i")
        }
        open(release("rar", volumes)).use { assertArrayEquals(payload, it.bytes()) }

        val packed = RarTestBuilder.rar5("Movie.mkv", payload, 50_000, compressed = true).mapIndexed { i, bytes ->
            nntp.addFile(bytes, partSize = 2_000, name = "Packed.part0${i + 1}.rar", idPrefix = "packed$i")
        }
        val token = release("packed", packed)
        assertThrows<ArchiveRefusedException> { open(token) }
        assertTrue(health.get(token) is ReleaseHealth.NotStreamable)
    }

    @Test
    fun `the census catches a broken release within the verify budget under Strict`() {
        performance { it.copy(verifyBudgetMillis = 3_000, damagePolicy = DamagePolicy.STRICT) }
        val file = nntp.addFile(payload, partSize = 2_000, name = "Broken.mkv", idPrefix = "broken")
        nntp.remove(file.segments[7].messageId)
        val token = release("broken", listOf(file))
        assertThrows<UsenetUnplayableException> { open(token) }
        assertTrue(health.get(token) is ReleaseHealth.Broken)
    }

    @Test
    fun `under Tolerant a small hole is filled with an EBML Void and playback continues`() {
        performance { it.copy(verifyBudgetMillis = 3_000, damagePolicy = DamagePolicy.TOLERANT) }
        val big = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()) + Random(8).nextBytes(400_000)
        val file = nntp.addFile(big, partSize = 2_000, name = "Holey.mkv", idPrefix = "holey")
        nntp.remove(file.segments[50].messageId)
        open(release("holey", listOf(file))).use { session ->
            val bytes = session.bytes()
            assertArrayEquals(big.copyOfRange(0, 100_000), bytes.copyOfRange(0, 100_000))
            assertEquals(0xEC.toByte(), bytes[100_000])
            assertArrayEquals(big.copyOfRange(102_000, big.size), bytes.copyOfRange(102_000, big.size))
        }
    }

    @Test
    fun `verify file contents refuses a release that isn't a video`() {
        performance { it.copy(verifyFileContents = true) }
        val token = release("exe", listOf(nntp.addFile("MZ".toByteArray() + ByteArray(5_000), partSize = 2_000, name = "Setup.mkv", idPrefix = "exe")))
        assertThrows<UsenetUnplayableException> { open(token) }
    }

    @Test
    fun `an unknown token is refused`() {
        assertThrows<UsenetUnplayableException> { open("0".repeat(32)) }
    }
}

private fun UsenetSession.bytes(): ByteArray {
    val session = this
    return object : com.seastreams.tv.usenet.archive.RandomAccessData {
        override fun size() = session.size
        override fun read(position: Long, target: ByteArray, offset: Int, length: Int) = session.read(position, target, offset, length)
    }.readAll(3_000)
}
