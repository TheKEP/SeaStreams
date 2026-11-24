package com.seastreams.tv.usenet.session

import com.seastreams.tv.testutil.FakeNntpServer
import com.seastreams.tv.testutil.SevenZipFixtures
import com.seastreams.tv.usenet.archive.ArchiveRefusedException
import com.seastreams.tv.usenet.archive.readAll
import com.seastreams.tv.usenet.nntp.DefaultSocketOpener
import com.seastreams.tv.usenet.nntp.NntpEngine
import com.seastreams.tv.usenet.nntp.NntpEngineSettings
import com.seastreams.tv.usenet.nntp.monotonicMillis
import com.seastreams.tv.usenet.stream.SegmentArena
import com.seastreams.tv.usenet.stream.UsenetFile
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.Executors

/** 7z releases through the real NNTP read path: posted volumes → [ReleaseAssembler] → playable bytes (PRD §12). */
class ReleaseAssemblerSevenZipTest {
    private val server = FakeNntpServer()
    private val engine = NntpEngine(DefaultSocketOpener, monotonicMillis, null).apply {
        configure(listOf(server.endpoint(maxConnections = 4)), NntpEngineSettings(dialTimeoutMs = 2_000, stallTimeoutMs = 2_000, dialBackoffMs = 20))
    }
    private val arena = SegmentArena(64L * 1024 * 1024)
    private val pool = Executors.newFixedThreadPool(4)
    private val payload = SevenZipFixtures.payload()
    private var next = 0

    @AfterEach
    fun tearDown() {
        engine.close()
        server.close()
        pool.shutdownNow()
    }

    private fun posted(name: String, data: ByteArray, subject: String = name): PostedVolume {
        val nzb = server.addFile(data, partSize = 7_000, name = name, idPrefix = "z${next++}")
        val file = nzb.copy(subject = "\"$subject\" yEnc (1/${nzb.segments.size})")
        return PostedVolume(UsenetFile(file, engine, arena, readAheadSegments = 4, segmentTimeoutMs = 5_000), file.filename ?: subject)
    }

    private fun assembler() = ReleaseAssembler(pool, lazyRar = true, strictMembership = false)

    @Test
    fun `a split 7z set plays byte for byte and the read-ahead follows its volumes`() {
        val volumes = SevenZipFixtures.SPLIT.map { posted(it, SevenZipFixtures.bytes(it)) } + posted("Movie.nfo", ByteArray(50))
        val media = assembler().assemble(volumes.reversed())
        assertEquals(SevenZipFixtures.VIDEO_NAME, media.name)
        assertEquals(listOf(3, 2, 1), media.files)
        assertNotNull(media.follow)
        assertArrayEquals(payload, media.data.readAll(5_000))
    }

    @Test
    fun `a header-encrypted 7z is unlocked with the NZB password`() {
        val volume = posted("Movie.7z", SevenZipFixtures.bytes("encrypted-headers.7z"))
        val media = assembler().assemble(listOf(volume), SevenZipFixtures.PASSWORD)
        assertEquals(SevenZipFixtures.VIDEO_NAME, media.name)
        assertNotNull(media.focus)
        assertArrayEquals(payload, media.data.readAll(3_000))
    }

    @Test
    fun `an obfuscated 7z is found by its signature and refused without the password`() {
        val volume = posted("a1b2c3d4e5", SevenZipFixtures.bytes("encrypted-headers.7z"), subject = "a1b2c3d4e5")
        val error = assertThrows<ArchiveRefusedException> { assembler().assemble(listOf(volume)) }
        assertEquals("The archive is password-protected", error.message)
    }

    @Test
    fun `a 7z inside raw mkv splits is read as 7z, and a compressed one is refused`() {
        val stored = SevenZipFixtures.SPLIT.mapIndexed { i, name -> posted("Movie.mkv.00${i + 1}", SevenZipFixtures.bytes(name)) }
        assertArrayEquals(payload, assembler().assemble(stored).data.readAll(8_000))
        val compressed = posted("Other.7z", SevenZipFixtures.bytes("compressed.7z"))
        val error = assertThrows<ArchiveRefusedException> { assembler().assemble(listOf(compressed)) }
        assertEquals("The 7z archive is compressed (only stored archives can stream)", error.message)
    }
}
