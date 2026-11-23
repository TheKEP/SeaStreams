package com.seastreams.tv.data.debrid.api

import com.seastreams.tv.testutil.TestHttpServer
import com.seastreams.tv.testutil.debridEndpoints
import com.seastreams.tv.testutil.json
import com.seastreams.tv.testutil.testDebridHttp
import io.ktor.server.routing.get
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock

/** TorBox and Premiumize library clients against JSON fixtures served by a real local HTTP server. */
class TorBoxPremiumizeClientTest {

    private val server = TestHttpServer {
        // TorBox torrents: page 1 full (1,000), page 2 ends; #2 unfinished, #3 no longer present.
        get("/tb/torrents/mylist") {
            val offset = call.request.queryParameters["offset"]?.toInt() ?: 0
            val ids = if (offset == 0) (1..1_000) else (1_001..1_002)
            call.json(
                ids.joinToString(",", """{"success":true,"data":[""", "]}") { i ->
                    val finished = i != 2
                    val present = i != 3
                    """{"id":$i,"name":"Movie $i","size":10,"download_finished":$finished,"download_present":$present,
                        "files":[{"id":0,"name":"Movie $i/movie.mkv","short_name":"movie.mkv","size":10}]}"""
                },
            )
        }
        get("/tb/usenet/mylist") {
            call.json("""{"success":true,"data":[{"id":7,"name":"Show.S01E02","download_finished":true,"files":[{"id":4,"name":"Show.S01E02.mkv","size":5}]}]}""")
        }
        get("/tb/webdl/mylist") { call.json("""{"success":true,"data":[]}""") }
        get("/tb/{section}/requestdl") {
            val id = call.request.queryParameters["torrent_id"] ?: call.request.queryParameters["usenet_id"]
            when (id) {
                "1" -> call.json("""{"success":true,"data":"https://store.torbox.app/1/movie.mkv"}""")
                "7" -> call.json("""{"success":true,"data":"http://store.torbox.app/7/show.mkv"}""")
                else -> call.json("""{"success":false,"error":"ITEM_NOT_FOUND"}""")
            }
        }
        // Premiumize: cloud files with paths, a transfer whose file isn't listed yet, a running transfer.
        get("/pm/item/listall") {
            call.json(
                """{"status":"success","files":[
                   {"id":"f1","name":"Inception.2010.mkv","size":5000,"path":"Movies/Inception (2010)/Inception.2010.mkv"},
                   {"id":"f2","name":"Show.S01E01.mkv","size":900,"path":"Show.S01E01.mkv"}]}""",
            )
        }
        get("/pm/transfer/list") {
            call.json(
                """{"status":"success","transfers":[
                   {"id":"x1","name":"Late.Film.2021.mkv","status":"finished","file_id":"f9"},
                   {"id":"x2","name":"Busy.2022","status":"running","file_id":"f8"},
                   {"id":"x3","name":"Inception","status":"finished","file_id":"f1"}]}""",
            )
        }
        get("/pm/item/details") {
            when (call.request.queryParameters["id"]) {
                "f1" -> call.json("""{"id":"f1","name":"Inception.2010.mkv","link":"https://energy.premiumize.me/f1"}""")
                "f2" -> call.json("""{"id":"f2","name":"Show.S01E01.mkv","stream_link":"http://energy.premiumize.me/f2"}""")
                else -> call.json("""{"status":"error","message":"Item not found"}""")
            }
        }
    }

    private val tb = TorBoxClient(testDebridHttp(), server.debridEndpoints())
    private val pm = PremiumizeClient(testDebridHttp(), server.debridEndpoints(), Clock.systemUTC())

    @AfterEach
    fun tearDown() = server.close()

    @Test
    fun `TorBox lists finished, present items of every section across pages, files included`() = runBlocking<Unit> {
        val items = tb.library("tb-key")

        assertEquals(1_000, items.count { it.id.startsWith("torrents:") }) // 1,002 listed, #2 and #3 skipped
        assertEquals(listOf("usenet:7"), items.filter { it.id.startsWith("usenet:") }.map { it.id })
        assertEquals(listOf("0", "1000"), server.requests.filter { it.path == "/tb/torrents/mylist" }.map { it.queryParam("offset") })
        assertTrue(server.requests.filter { it.path.endsWith("/mylist") }.all { it.queryParam("bypass_cache") == "true" })
        val first = items.first { it.id == "torrents:1" }
        assertEquals(listOf("Movie 1/movie.mkv"), tb.files("tb-key", first).map { it.path })
    }

    @Test
    fun `TorBox resolves with the section's id key and refuses deleted items and plain HTTP`() = runBlocking<Unit> {
        assertEquals("https://store.torbox.app/1/movie.mkv", tb.resolve("tb-key", DebridFileHandle("torrents:1", "0", null)))
        val request = server.requests.last { it.path == "/tb/torrents/requestdl" }
        assertEquals("false", request.queryParam("redirect"))
        assertEquals("0", request.queryParam("file_id"))

        assertThrows<DebridServiceException> { tb.resolve("tb-key", DebridFileHandle("usenet:7", "4", null)) }
        assertThrows<DebridNotFoundException> { tb.resolve("tb-key", DebridFileHandle("torrents:5", "0", null)) }
    }

    @Test
    fun `Premiumize lists cloud files by full path plus finished transfers not listed yet`() = runBlocking<Unit> {
        val items = pm.library("pm-key")

        assertEquals(listOf("Movies/Inception (2010)/Inception.2010.mkv", "Show.S01E01.mkv", "Late.Film.2021.mkv"), items.map { it.name })
        assertEquals(listOf("f1", "f2", "f9"), items.map { it.id })
        assertEquals(items[0].files, pm.files("pm-key", items[0]))
    }

    @Test
    fun `Premiumize resolves the item link and refuses deleted items and plain HTTP`() = runBlocking<Unit> {
        assertEquals("https://energy.premiumize.me/f1", pm.resolve("pm-key", DebridFileHandle("f1", "f1", null)))
        assertThrows<DebridServiceException> { pm.resolve("pm-key", DebridFileHandle("f2", "f2", null)) }
        assertThrows<DebridNotFoundException> { pm.resolve("pm-key", DebridFileHandle("f7", "f7", null)) }
    }
}
