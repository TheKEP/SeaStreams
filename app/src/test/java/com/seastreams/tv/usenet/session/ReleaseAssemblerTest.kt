package com.seastreams.tv.usenet.session

import com.seastreams.tv.testutil.FakeNntpServer
import com.seastreams.tv.testutil.RarTestBuilder
import com.seastreams.tv.usenet.archive.ArchiveRefusedException
import com.seastreams.tv.usenet.archive.RarVolumeNames
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.Executors
import kotlin.random.Random

class ReleaseAssemblerTest {
    private val server = FakeNntpServer()
    private val engine = NntpEngine(DefaultSocketOpener, monotonicMillis, null).apply {
        configure(listOf(server.endpoint(maxConnections = 4)), NntpEngineSettings(dialTimeoutMs = 2_000, stallTimeoutMs = 2_000, dialBackoffMs = 20))
    }
    private val arena = SegmentArena(64L * 1024 * 1024)
    private val pool = Executors.newFixedThreadPool(4)
    private val payload = Random(11).nextBytes(60_000)
    private var next = 0

    @AfterEach
    fun tearDown() {
        engine.close()
        server.close()
        pool.shutdownNow()
    }

    /** Posts [data] as yEnc file [name] whose NZB subject reads [subject] (obfuscated when they differ). */
    private fun posted(name: String, data: ByteArray, subject: String = name): PostedVolume {
        val nzb = server.addFile(data, partSize = 3_000, name = name, idPrefix = "f${next++}")
        val file = nzb.copy(subject = "\"$subject\" yEnc (1/${nzb.segments.size})")
        return PostedVolume(UsenetFile(file, engine, arena, readAheadSegments = 4, segmentTimeoutMs = 5_000), file.filename ?: subject)
    }

    private fun assembler(lazy: Boolean = true, strict: Boolean = false) = ReleaseAssembler(pool, lazy, strict)

    @Test
    fun `an obfuscated RAR5 set is ordered by its yEnc names and plays byte for byte`() {
        val volumes = RarTestBuilder.rar5("Movie.2024.2160p.mkv", payload, 9_000).mapIndexed { i, bytes ->
            posted("Movie.2024.part%02d.rar".format(i + 1), bytes, subject = "b7e1${'a' + i}9f02c4d")
        }.shuffled(Random(3))
        val media = assembler().assemble(volumes.onEach { it.file.tailPrefetch = false })
        assertEquals("Movie.2024.2160p.mkv", media.name)
        assertArrayEquals(payload, media.data.readAll(5_000))
        // Only the set's last volume prefetches its tail (where a player looks for the container index).
        val last = volumes.single { it.name == "Movie.2024.part07.rar" }
        assertEquals(listOf(last), volumes.filter { it.file.tailPrefetch })
    }

    @Test
    fun `only the volume being played reads ahead, across volume boundaries and seeks`() {
        val volumes = RarTestBuilder.rar5("Movie.2024.2160p.mkv", payload, 9_000).mapIndexed { i, bytes ->
            posted("Movie.2024.part%02d.rar".format(i + 1), bytes)
        }
        val media = assembler(lazy = false).assemble(volumes)
        assertTrue(volumes.none { it.file.readAhead }, "header reads of every volume start no read-ahead")
        val focus = checkNotNull(media.focus)
        val buffer = ByteArray(1_000)
        var position = 0L
        while (true) {
            val n = media.data.read(position, buffer, 0, buffer.size)
            if (n < 0) break
            position += n
            assertEquals(1, volumes.count { it.file.readAhead }, "one window at byte $position")
            assertTrue(checkNotNull(focus.activeVolume()).file.readAhead)
        }
        assertEquals(payload.size.toLong(), position)
        media.data.read(10L, buffer, 0, 10) // seek back to the first volume
        assertEquals(listOf(volumes[0]), volumes.filter { it.file.readAhead })
    }

    @Test
    fun `an old-style RAR4 set rolls over from r99 to s00`() {
        val small = Random(5).nextBytes(103 * 100)
        val volumes = RarTestBuilder.rar4("Show.S01E01.mkv", small, 100).mapIndexed { i, bytes ->
            posted("show." + RarVolumeNames.oldExtension(i), bytes)
        }
        for (lazy in listOf(true, false)) {
            val media = assembler(lazy = lazy).assemble(volumes)
            assertEquals(103, media.files.size)
            assertArrayEquals(small, media.data.readAll(1_000))
        }
    }

    @Test
    fun `raw 001 splits are joined end to end`() {
        val parts = (0 until 4).map { i -> posted("Movie.mkv.%03d".format(i + 1), payload.copyOfRange(i * 15_000, (i + 1) * 15_000)) }
        val media = assembler().assemble(parts.reversed())
        assertEquals("Movie.mkv", media.name)
        assertArrayEquals(payload, media.data.readAll())
    }

    @Test
    fun `a 7z hidden behind 001 names is refused by signature`() {
        val seven = byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C) + ByteArray(5_000)
        val parts = listOf(posted("Movie.001", seven), posted("Movie.002", ByteArray(5_006)))
        assertThrows<ArchiveRefusedException> { assembler().assemble(parts) }
    }

    @Test
    fun `a plain video is read straight from its posted file`() {
        val media = assembler().assemble(listOf(posted("Movie.nfo", ByteArray(100)), posted("Movie.mkv", payload)))
        assertEquals("Movie.mkv", media.name)
        assertArrayEquals(payload, media.data.readAll())
    }
}
