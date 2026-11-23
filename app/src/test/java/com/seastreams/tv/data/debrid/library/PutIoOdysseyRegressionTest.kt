package com.seastreams.tv.data.debrid.library

import com.seastreams.tv.data.debrid.api.PutIoClient
import com.seastreams.tv.data.usenet.newznab.UsenetSearchTarget
import com.seastreams.tv.domain.streams.parse.ReleaseParser
import com.seastreams.tv.testutil.DebridLibraryRig
import com.seastreams.tv.testutil.TestHttpServer
import com.seastreams.tv.testutil.debridEndpoints
import com.seastreams.tv.testutil.json
import com.seastreams.tv.testutil.testDebridHttp
import io.ktor.server.routing.get
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Clock

/**
 * Regression: a put.io root folder `The Odyssey (2026) (1080p BluRay x265 10bit EAC3 5 1 SAMPA)` (7.98 GB) gave no
 * Debrid result for *The Odyssey* (2026). The name parsed and matched fine; the client only accepted children with
 * `file_type` `VIDEO`, while put.io types files it hasn't identified (common for x265 10-bit MKVs) as `FILE`. The
 * fixtures copy put.io's `/v2/files/list` shape (`file_type`, `content_type`, `extension`, `parent_id`, no per-file
 * status).
 */
class PutIoOdysseyRegressionTest {

    /** The video's `file_type`, `content_type`, `extension` and name, per test. */
    private var video = Video("VIDEO", "video/x-matroska", "mkv", "$FOLDER.mkv")

    private data class Video(val fileType: String, val contentType: String?, val extension: String?, val name: String)

    private val server = TestHttpServer {
        get("/putio/files/list") {
            when (call.request.queryParameters["parent_id"]) {
                "0" -> call.json(
                    """{"cursor":null,"files":[{"content_type":"application/x-directory","crc32":null,"created_at":"2026-07-20T10:00:00",
                       "extension":null,"file_type":"FOLDER","folder_type":"REGULAR","id":1234567890,"is_hidden":false,"is_mp4_available":false,
                       "is_shared":false,"name":"$FOLDER","opensubtitles_hash":null,"parent_id":0,"screenshot":null,"size":8568412160,
                       "updated_at":"2026-07-20T10:05:00"}],
                       "parent":{"content_type":"application/x-directory","file_type":"FOLDER","id":0,"name":"My Files","parent_id":null,"size":8568412160},
                       "total":1}""",
                )
                "1234567890" -> {
                    val contentType = video.contentType?.let { "\"$it\"" } ?: "null"
                    val extension = video.extension?.let { "\"$it\"" } ?: "null"
                    call.json(
                        """{"cursor":null,"files":[
                           {"content_type":$contentType,"crc32":"0a1b2c3d","created_at":"2026-07-20T10:00:00","extension":$extension,
                            "file_type":"${video.fileType}","id":1234567891,"is_mp4_available":false,"name":"${video.name}",
                            "parent_id":1234567890,"size":8568000000},
                           {"content_type":"text/plain","extension":"txt","file_type":"TEXT","id":1234567892,"name":"RARBG.txt","parent_id":1234567890,"size":30},
                           {"content_type":"image/jpeg","extension":"jpg","file_type":"IMAGE","id":1234567893,"name":"poster.jpg","parent_id":1234567890,"size":400000}],
                           "parent":{"content_type":"application/x-directory","file_type":"FOLDER","id":1234567890,"name":"$FOLDER","parent_id":0,"size":8568412160},
                           "total":3}""",
                    )
                }
                else -> call.json("""{"error_type":"NotFound","status":"ERROR"}""")
            }
        }
    }

    @AfterEach
    fun tearDown() = server.close()

    private suspend fun search(): List<String?> {
        val rig = DebridLibraryRig(Clock.systemUTC())
        rig.add(PutIoClient(testDebridHttp(), server.debridEndpoints()))
        rig.enable()
        return rig.search.search(UsenetSearchTarget.Movie("tt33764258", "The Odyssey", 2026)).streams.map { it.hints.filename }
    }

    @Test
    fun `the exact folder name parses to The Odyssey (2026) and matches the movie`() {
        val parsed = ReleaseParser.parse(FOLDER)
        assertEquals("The Odyssey" to "2026", parsed.title to parsed.year)
        assertEquals("1080p", parsed.resolution)

        val ranked = DebridLibraryMatcher.rank(listOf(com.seastreams.tv.data.debrid.api.DebridItem("1", FOLDER)), UsenetSearchTarget.Movie(null, "The Odyssey", 2026))
        assertEquals(1, ranked.size)
        assertEquals(emptyList<Any>(), DebridLibraryMatcher.rank(ranked.map { it.item }, UsenetSearchTarget.Movie(null, "The Odyssey", 1997)))
    }

    @ParameterizedTest(name = "file_type {0}, content_type {1}")
    @CsvSource(
        "VIDEO, video/x-matroska, mkv",
        "FILE, video/x-matroska, mkv", // put.io hasn't identified the MKV
        "FILE, application/octet-stream, mkv", // only the extension says video
    )
    fun `the Odyssey folder's video is found whatever put io typed it`(fileType: String, contentType: String, extension: String) = runBlocking<Unit> {
        video = Video(fileType, contentType, extension, "$FOLDER.$extension")
        assertEquals(listOf("$FOLDER.$extension"), search())
    }

    @Test
    fun `an m2ts with no content type and a name without extension still count, by put io's extension field`() = runBlocking<Unit> {
        video = Video("FILE", null, "m2ts", "$FOLDER.m2ts")
        assertEquals(listOf("$FOLDER.m2ts"), search())
        video = Video("VIDEO", null, "mkv", FOLDER)
        assertEquals(listOf("$FOLDER.mkv"), search())
    }

    @Test
    fun `text and images in the folder never become streams`() = runBlocking<Unit> {
        video = Video("TEXT", "text/plain", "nfo", "$FOLDER.nfo")
        assertEquals(emptyList<String?>(), search())
    }

    private companion object {
        const val FOLDER = "The Odyssey (2026) (1080p BluRay x265 10bit EAC3 5 1 SAMPA)"
    }
}
