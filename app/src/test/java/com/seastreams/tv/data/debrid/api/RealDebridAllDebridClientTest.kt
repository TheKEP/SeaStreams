package com.seastreams.tv.data.debrid.api

import com.seastreams.tv.testutil.TestHttpServer
import com.seastreams.tv.testutil.debridEndpoints
import com.seastreams.tv.testutil.json
import com.seastreams.tv.testutil.testDebridHttp
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveParameters
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Real-Debrid and AllDebrid library clients against JSON fixtures served by a real local HTTP server. */
class RealDebridAllDebridClientTest {

    private val server = TestHttpServer {
        // Real-Debrid: page 1 is full (500), page 2 ends the listing; T2 is still downloading.
        get("/rd/torrents") {
            val page = call.request.queryParameters["page"]?.toInt() ?: 1
            val ids = if (page == 1) (1..500) else (501..502)
            call.json(
                ids.joinToString(",", "[", "]") { i ->
                    val status = if (i == 2) "downloading" else "downloaded"
                    """{"id":"T$i","filename":"Movie.$i.2010.1080p","hash":"ignored","bytes":100,"status":"$status","links":["https://real-debrid.com/d/T$i"]}"""
                },
            )
        }
        get("/rd/downloads") {
            call.json(
                """[{"id":"D1","filename":"Inception.2010.mkv","filesize":5000,"link":"https://hoster.example/inception","download":"https://cdn/x"},
                   {"id":"D2","filename":"dup.mkv","filesize":1,"link":"https://real-debrid.com/d/T1","download":"https://cdn/y"}]""",
            )
        }
        get("/rd/torrents/info/{id}") {
            when (call.parameters["id"]) {
                "T1" -> call.json(
                    """{"id":"T1","status":"downloaded","links":["https://real-debrid.com/d/L1","https://real-debrid.com/d/L3"],
                       "files":[{"id":1,"path":"/Show.S01/Show.S01E01.mkv","bytes":900,"selected":1},
                                {"id":2,"path":"/Show.S01/info.nfo","bytes":1,"selected":0},
                                {"id":3,"path":"/Show.S01/Show.S01E02.mkv","bytes":800,"selected":1}]}""",
                )
                else -> call.json("""{"error":"unknown_ressource","error_code":7}""", HttpStatusCode.NotFound)
            }
        }
        post("/rd/unrestrict/link") {
            when (call.receiveParameters()["link"]) {
                "https://real-debrid.com/d/L3" -> call.json("""{"id":"U","download":"https://dl.real-debrid.com/abc/Show.S01E02.mkv"}""")
                "https://hoster.example/inception" -> call.json("""{"id":"U","download":"http://insecure.example/inception.mkv"}""")
                else -> call.json("""{"error":"unavailable_file","error_code":24}""", HttpStatusCode.ServiceUnavailable)
            }
        }
        // AllDebrid: one ready magnet, one still downloading; one saved link.
        get("/ad/v4.1/magnet/status") {
            call.json(
                """{"status":"success","data":{"magnets":[
                   {"id":11,"filename":"Show.S01.1080p","size":2000,"status":"Ready","statusCode":4},
                   {"id":12,"filename":"Other.2020","size":10,"status":"Downloading","statusCode":1}]}}""",
            )
        }
        get("/ad/v4/user/links") {
            call.json("""{"status":"success","data":{"links":[{"link":"https://uptobox.example/abc","filename":"Film.2019.mkv","size":700}]}}""")
        }
        get("/ad/v4/magnet/files") {
            when (call.request.queryParameters["id[]"]) {
                "11" -> call.json(
                    """{"status":"success","data":{"magnets":[{"id":"11","files":[
                       {"n":"Show.S01","e":[{"n":"Show.S01E01.mkv","s":900,"l":"https://alldebrid.com/f/A1"},
                                           {"n":"Extras","e":[{"n":"Sample.mkv","s":5,"l":"https://alldebrid.com/f/A2"}]}]}]}]}}""",
                )
                else -> call.json("""{"status":"success","data":{"magnets":[{"id":"99","error":{"code":"MAGNET_INVALID_ID"}}]}}""")
            }
        }
        get("/ad/v4/link/unlock") {
            when (call.request.queryParameters["link"]) {
                "https://alldebrid.com/f/A1" -> call.json("""{"status":"success","data":{"link":"https://cdn.alldebrid.com/Show.S01E01.mkv"}}""")
                "https://uptobox.example/abc" -> call.json("""{"status":"success","data":{"link":"ftp://nope/Film.mkv"}}""")
                else -> call.json("""{"status":"error","error":{"code":"LINK_DOWN"}}""")
            }
        }
    }

    private val rd = RealDebridClient(testDebridHttp(), server.debridEndpoints())
    private val ad = AllDebridClient(testDebridHttp(), server.debridEndpoints())

    @AfterEach
    fun tearDown() = server.close()

    @Test
    fun `Real-Debrid lists finished torrents across pages plus downloads that aren't torrent links`() = runBlocking<Unit> {
        val items = rd.library("rd-token")

        val torrents = items.filter { it.id.startsWith("t:") }
        assertEquals(501, torrents.size) // 502 listed, T2 still downloading
        assertFalse(torrents.any { it.id == "t:T2" })
        assertEquals(listOf("1", "2"), server.requests.filter { it.path == "/rd/torrents" }.map { it.queryParam("page") })
        val downloads = items.filter { it.id.startsWith("d:") }
        assertEquals(listOf("Inception.2010.mkv"), downloads.map { it.name })
        assertEquals("https://hoster.example/inception", downloads.single().files?.single()?.link)
    }

    @Test
    fun `Real-Debrid pairs selected files with links and resolves through unrestrict`() = runBlocking<Unit> {
        val files = rd.files("rd-token", DebridItem("t:T1", "Show.S01"))
        assertEquals(listOf("Show.S01/Show.S01E01.mkv", "Show.S01/Show.S01E02.mkv"), files.map { it.path })
        assertEquals("https://real-debrid.com/d/L3", files[1].link)

        assertEquals("https://dl.real-debrid.com/abc/Show.S01E02.mkv", rd.resolve("rd-token", DebridFileHandle("t:T1", "3", null)))
    }

    @Test
    fun `Real-Debrid refuses a deleted torrent and a non-HTTPS link`() = runBlocking<Unit> {
        assertThrows<DebridNotFoundException> { rd.resolve("rd-token", DebridFileHandle("t:GONE", "1", null)) }
        assertThrows<DebridServiceException> { rd.resolve("rd-token", DebridFileHandle("d:D1", "D1", "https://hoster.example/inception")) }
    }

    @Test
    fun `AllDebrid lists ready magnets and saved links, flattens nested files and resolves`() = runBlocking<Unit> {
        val items = ad.library("ad-key")
        assertEquals(listOf("m:11", "l:https://uptobox.example/abc"), items.map { it.id })
        assertTrue(server.requests.all { it.queryParam("agent") == ALL_DEBRID_AGENT })

        val files = ad.files("ad-key", items.first())
        assertEquals(listOf("Show.S01/Show.S01E01.mkv", "Show.S01/Extras/Sample.mkv"), files.map { it.path })
        assertEquals("https://cdn.alldebrid.com/Show.S01E01.mkv", ad.resolve("ad-key", DebridFileHandle("m:11", files.first().id, null)))
    }

    @Test
    fun `AllDebrid refuses a deleted magnet, a dead link and a non-HTTPS link`() = runBlocking<Unit> {
        assertThrows<DebridNotFoundException> { ad.files("ad-key", DebridItem("m:99", "gone")) }
        assertThrows<DebridNotFoundException> { ad.resolve("ad-key", DebridFileHandle("l:x", "0", "https://dead.example/x")) }
        assertThrows<DebridServiceException> { ad.resolve("ad-key", DebridFileHandle("l:x", "0", "https://uptobox.example/abc")) }
    }
}
