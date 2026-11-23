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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock

/** Debrid-Link, Offcloud and put.io library clients against JSON fixtures served by a real local HTTP server. */
class DebridLinkOffcloudPutIoClientTest {

    private val server = TestHttpServer {
        // Debrid-Link: two seedbox pages (one torrent still at 40%), one downloader link that expired.
        get("/dl/v2/seedbox/list") {
            val ids = call.request.queryParameters["ids"]
            val page = call.request.queryParameters["page"]?.toInt() ?: 0
            val torrents = when {
                ids == "s1" -> listOf(torrent("s1", 100))
                ids != null -> emptyList()
                page == 0 -> (1..50).map { torrent("s$it", if (it == 2) 40 else 100) }
                else -> listOf(torrent("s51", 100))
            }
            call.json("""{"success":true,"value":${torrents.joinToString(",", "[", "]")},"pagination":{"page":$page,"pages":2}}""")
        }
        get("/dl/v2/downloader/list") {
            call.json(
                """{"success":true,"value":[
                   {"id":"d1","name":"Film.2019.mkv","size":700,"downloadUrl":"https://dl.debrid-link.com/d1/Film.2019.mkv","expired":false},
                   {"id":"d2","name":"Old.2001.mkv","size":700,"downloadUrl":"https://dl.debrid-link.com/d2","expired":true}],
                   "pagination":{"page":0,"pages":1}}""",
            )
        }
        // Offcloud: a finished folder, a finished single file, a running request.
        get("/oc/cloud/history") {
            call.json(
                """[{"requestId":"folder000001","fileName":"Show.S01","status":"downloaded","isDirectory":true,"server":"s1"},
                    {"requestId":"single000002","fileName":"Film 2019.mkv","status":"downloaded","isDirectory":false,"server":"s2","fileSize":900},
                    {"requestId":"running00003","fileName":"Busy.2020","status":"downloading","isDirectory":true}]""",
            )
        }
        get("/oc/cloud/explore/{id}") {
            when (call.parameters["id"]) {
                "folder000001" -> call.json(
                    """["https://s1.offcloud.com/cloud/download/folder000001/Show.S01/Show.S01E01.mkv",
                        "http://s1.offcloud.com/cloud/download/folder000001/Show.S01/Show.S01E02.mkv"]""",
                )
                else -> call.json("""{"error":"Request not found"}""", HttpStatusCode.NotFound)
            }
        }
        // put.io: the top level continues through a cursor; a folder holds a nested season folder.
        get("/putio/files/list") {
            when (call.request.queryParameters["parent_id"]) {
                "0" -> call.json(
                    """{"status":"OK","cursor":"next","files":[
                       {"id":10,"name":"Show","file_type":"FOLDER","size":0},
                       {"id":11,"name":"notes.txt","file_type":"TEXT","size":1}]}""",
                )
                "10" -> call.json("""{"status":"OK","cursor":null,"files":[{"id":20,"name":"Season 1","file_type":"FOLDER"},{"id":21,"name":"Show.S02E01.mkv","file_type":"VIDEO","size":800}]}""")
                "20" -> call.json("""{"status":"OK","cursor":null,"files":[{"id":30,"name":"Show.S01E01.mkv","file_type":"VIDEO","size":900}]}""")
                else -> call.json("""{"error_type":"NotFound"}""", HttpStatusCode.NotFound)
            }
        }
        post("/putio/files/list/continue") {
            assertEquals("next", call.receiveParameters()["cursor"])
            call.json("""{"status":"OK","cursor":null,"files":[{"id":12,"name":"Film.2019.mkv","file_type":"VIDEO","size":700}]}""")
        }
        get("/putio/files/{id}/url") {
            when (call.parameters["id"]) {
                "30" -> call.json("""{"status":"OK","url":"https://s100.put.io/zipstream/30.mkv"}""")
                "12" -> call.json("""{"status":"OK","url":"http://s100.put.io/12.mkv"}""")
                else -> call.json("""{"error_type":"NotFound"}""", HttpStatusCode.NotFound)
            }
        }
    }

    private val dl = DebridLinkClient(testDebridHttp(), server.debridEndpoints(), Clock.systemUTC())
    private val oc = OffcloudClient(testDebridHttp(), server.debridEndpoints())
    private val putIo = PutIoClient(testDebridHttp(), server.debridEndpoints())

    @AfterEach
    fun tearDown() = server.close()

    @Test
    fun `Debrid-Link lists finished seedbox torrents across pages plus unexpired links, and resolves by id`() = runBlocking<Unit> {
        val items = dl.library("dl-token")

        assertEquals(50, items.count { it.id.startsWith("s:") }) // 51 listed, s2 at 40%
        assertEquals(listOf("d:d1"), items.filter { it.id.startsWith("d:") }.map { it.id })
        assertEquals(listOf("0", "1"), server.requests.filter { it.path == "/dl/v2/seedbox/list" }.map { it.queryParam("page") })

        val file = dl.files("dl-token", items.first { it.id == "s:s1" }).single()
        assertEquals("https://dl.debrid-link.com/s1/Movie.mkv", dl.resolve("dl-token", DebridFileHandle("s:s1", file.id, file.link)))
        assertThrows<DebridNotFoundException> { dl.resolve("dl-token", DebridFileHandle("s:s9", "f", null)) }
    }

    @Test
    fun `Offcloud lists finished requests, explores folders over HTTPS only and resolves`() = runBlocking<Unit> {
        val items = oc.library("oc-key")
        assertEquals(listOf("folder000001", "single000002"), items.map { it.id })
        assertNull(items[0].files)
        val single = items[1].files?.single()
        assertEquals("https://s2.offcloud.com/cloud/download/single000002/Film%202019.mkv", single?.link)

        val files = oc.files("oc-key", items[0])
        assertEquals(listOf("Show.S01E01.mkv"), files.map { it.name }) // the plain-HTTP entry is refused
        assertEquals(files[0].link, oc.resolve("oc-key", DebridFileHandle("folder000001", files[0].id, files[0].link)))
        assertThrows<DebridNotFoundException> { oc.resolve("oc-key", DebridFileHandle("gone00000004", "x", "https://s1.offcloud.com/x")) }
    }

    @Test
    fun `put io lists the top level through the cursor, walks nested folders and resolves`() = runBlocking<Unit> {
        val items = putIo.library("putio-token")
        assertEquals(listOf("Show", "Film.2019.mkv"), items.map { it.name }) // the text file is no video

        val files = putIo.files("putio-token", items[0])
        assertEquals(listOf("Season 1/Show.S01E01.mkv", "Show.S02E01.mkv"), files.map { it.path })
        assertEquals("https://s100.put.io/zipstream/30.mkv", putIo.resolve("putio-token", DebridFileHandle("10", "30", null)))
        assertThrows<DebridServiceException> { putIo.resolve("putio-token", DebridFileHandle("12", "12", null)) }
        assertThrows<DebridNotFoundException> { putIo.resolve("putio-token", DebridFileHandle("99", "99", null)) }
    }

    private fun torrent(id: String, percent: Int) =
        """{"id":"$id","name":"Movie $id","totalSize":10,"downloadPercent":$percent,
            "files":[{"id":"$id-f","name":"Movie.mkv","size":10,"downloadPercent":$percent,"downloadUrl":"https://dl.debrid-link.com/$id/Movie.mkv"}]}"""
}
