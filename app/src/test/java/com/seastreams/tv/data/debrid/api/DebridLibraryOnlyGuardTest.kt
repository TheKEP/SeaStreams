package com.seastreams.tv.data.debrid.api

import com.seastreams.tv.data.debrid.DebridServiceKind
import com.seastreams.tv.testutil.TestHttpServer
import com.seastreams.tv.testutil.debridEndpoints
import com.seastreams.tv.testutil.json
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Clock
import java.util.concurrent.CopyOnWriteArrayList

/**
 * PRD §14 *Library-only rule*: no client ever sends a hash or magnet, asks whether something is cached, or adds a
 * torrent, link, NZB or transfer. Checked twice: every request every client makes on a full run (account → library →
 * files → resolve) is captured on the wire, and the Debrid sources are scanned for the services' add / cache-check
 * endpoints.
 */
class DebridLibraryOnlyGuardTest {

    private val sent = CopyOnWriteArrayList<String>()

    private val recorder = Interceptor { chain ->
        val request = chain.request()
        val body = request.body?.let { Buffer().also(it::writeTo).readUtf8() }.orEmpty()
        sent += "${request.method} ${request.url} $body"
        chain.proceed(request)
    }

    private val server = TestHttpServer {
        get("/rd/user") { call.json("""{"id":1,"type":"premium"}""") }
        get("/rd/torrents") { call.json("""[{"id":"T1","filename":"Film.2019","status":"downloaded","hash":"$HASH","links":["https://real-debrid.com/d/L1"]}]""") }
        get("/rd/downloads") { call.json("[]") }
        get("/rd/torrents/info/T1") {
            call.json("""{"id":"T1","status":"downloaded","hash":"$HASH","links":["https://real-debrid.com/d/L1"],"files":[{"id":1,"path":"/Film.2019.mkv","bytes":9,"selected":1}]}""")
        }
        post("/rd/unrestrict/link") { call.json("""{"download":"https://dl.real-debrid.com/x.mkv"}""") }
        get("/ad/v4/user") { call.json("""{"status":"success","data":{"user":{"isPremium":true}}}""") }
        get("/ad/v4.1/magnet/status") { call.json("""{"status":"success","data":{"magnets":[{"id":5,"hash":"$HASH","filename":"Film.2019","statusCode":4}]}}""") }
        get("/ad/v4/user/links") { call.json("""{"status":"success","data":{"links":[]}}""") }
        get("/ad/v4/magnet/files") { call.json("""{"status":"success","data":{"magnets":[{"id":"5","files":[{"n":"Film.2019.mkv","s":9,"l":"https://alldebrid.com/f/1"}]}]}}""") }
        get("/ad/v4/link/unlock") { call.json("""{"status":"success","data":{"link":"https://cdn.alldebrid.com/x.mkv"}}""") }
        get("/tb/user/me") { call.json("""{"success":true,"data":{"plan":1}}""") }
        get("/tb/{section}/mylist") {
            call.json("""{"success":true,"data":[{"id":3,"hash":"$HASH","name":"Film.2019","download_finished":true,"files":[{"id":0,"name":"Film.2019.mkv","size":9}]}]}""")
        }
        get("/tb/{section}/requestdl") { call.json("""{"success":true,"data":"https://store.torbox.app/x.mkv"}""") }
        get("/pm/account/info") { call.json("""{"status":"success","premium_until":4102444800}""") }
        get("/pm/item/listall") { call.json("""{"status":"success","files":[{"id":"f1","name":"Film.2019.mkv","size":9}]}""") }
        get("/pm/transfer/list") { call.json("""{"status":"success","transfers":[]}""") }
        get("/pm/item/details") { call.json("""{"id":"f1","link":"https://energy.premiumize.me/x.mkv"}""") }
        get("/dl/v2/account/infos") { call.json("""{"success":true,"value":{"accountType":1,"premiumLeft":100}}""") }
        get("/dl/v2/{section}/list") {
            call.json(
                """{"success":true,"value":[{"id":"s1","hashString":"$HASH","name":"Film.2019","downloadPercent":100,"downloadUrl":"https://dl.debrid-link.com/x",
                   "files":[{"id":"f","name":"Film.2019.mkv","size":9,"downloadPercent":100,"downloadUrl":"https://dl.debrid-link.com/x.mkv"}]}],"pagination":{"pages":1}}""",
            )
        }
        get("/oc/account/info") { call.json("""{"user_id":"u"}""") }
        get("/oc/cloud/history") { call.json("""[{"requestId":"abcdef123456","fileName":"Film.2019","status":"downloaded","isDirectory":true}]""") }
        get("/oc/cloud/explore/abcdef123456") { call.json("""["https://s1.offcloud.com/cloud/download/abcdef123456/Film.2019.mkv"]""") }
        get("/putio/account/info") { call.json("""{"status":"OK","info":{"account_active":true}}""") }
        get("/putio/files/list") { call.json("""{"status":"OK","files":[{"id":4,"name":"Film.2019.mkv","file_type":"VIDEO","size":9}]}""") }
        get("/putio/files/4/url") { call.json("""{"status":"OK","url":"https://s100.put.io/x.mkv"}""") }
    }

    @AfterEach
    fun tearDown() = server.close()

    @Test
    fun `a full run of every client sends no hash, magnet, cache check or add request`() = runBlocking<Unit> {
        val http = DebridHttp({ OkHttpClient.Builder().addInterceptor(recorder).build() }, Dispatchers.IO)
        val clients = DebridClients(http, server.debridEndpoints(), Clock.systemUTC())
        for (kind in DebridServiceKind.entries) {
            val client = clients.of(kind)
            client.account("key-0123456789")
            val items = client.library("key-0123456789")
            assertTrue(items.isNotEmpty(), "$kind listed nothing")
            for (item in items) {
                for (file in client.files("key-0123456789", item)) {
                    assertTrue(client.resolve("key-0123456789", DebridFileHandle(item.id, file.id, file.link)).startsWith("https://"))
                }
            }
        }

        assertTrue(sent.size >= DebridServiceKind.entries.size * 3)
        val offending = sent.filter { request -> FORBIDDEN.any { it.containsMatchIn(request) } }
        assertEquals(emptyList<String>(), offending)
        // Only reads: the one form post is Real-Debrid's unrestrict, of a link the user already owns.
        assertEquals(listOf("/rd/unrestrict/link"), sent.filter { it.startsWith("POST") }.map { it.substringAfter(server.baseUrl).substringBefore(' ') })
    }

    @Test
    fun `no Debrid source names an add, upload or cache-check endpoint`() {
        val root = listOf(File("src/main/java/com/seastreams/tv/data/debrid"), File("app/src/main/java/com/seastreams/tv/data/debrid"))
            .first { it.isDirectory }
        val sources = root.walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(sources.size >= 10)
        val hits = sources.flatMap { file ->
            val text = file.readText()
            SOURCE_FORBIDDEN.filter { it in text }.map { "${file.name}: $it" }
        }
        assertEquals(emptyList<String>(), hits)
    }

    private companion object {
        const val HASH = "0123456789abcdef0123456789abcdef01234567"

        /** Anything a request must never carry (the listings may *answer* with a hash; it is never sent back). */
        val FORBIDDEN = listOf(
            Regex("(?i)magnet:"), Regex("(?i)btih"), Regex("(?i)[0-9a-f]{40}"), Regex("(?i)[?&]hash(es)?="),
            Regex("(?i)instantavailability"), Regex("(?i)checkcached"), Regex("(?i)cache/(info|check|download)"),
            Regex("(?i)addmagnet|addtorrent|magnet/upload|torrents/add|createtorrent|seedbox/add|downloader/add"),
            Regex("(?i)transfer/create|directdl|usenet/createusenetdownload|webdl/createwebdownload|/cloud\\s"),
            Regex("(?i)files/upload|transfers/add|link/(save|delayed)"),
        )

        /** Endpoint names of the services' add / cache-check calls, which no Debrid source may contain. */
        val SOURCE_FORBIDDEN = listOf(
            "instantAvailability", "checkcached", "cache/info", "cache/download", "addMagnet", "magnet/upload",
            "torrents/add", "createtorrent", "seedbox/add", "downloader/add", "transfer/create", "transfer/directdl",
            "createusenetdownload", "createwebdownload", "files/upload", "transfers/add", "link/save", "magnet:?",
        )
    }
}
