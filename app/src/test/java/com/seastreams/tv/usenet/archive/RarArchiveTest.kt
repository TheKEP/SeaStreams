package com.seastreams.tv.usenet.archive

import com.seastreams.tv.testutil.RarTestBuilder
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/** In-memory volume that counts header-area reads (offset 0) so lazy resolution can be observed. */
class ByteVolume(override val name: String, val bytes: ByteArray) : VolumeData {
    val headReads = AtomicInteger()
    override val statedBytes: Long get() = bytes.size.toLong()

    override fun size(): Long = bytes.size.toLong()

    override fun read(position: Long, target: ByteArray, offset: Int, length: Int): Int {
        if (position >= bytes.size) return -1
        if (position < 64) headReads.incrementAndGet()
        val n = minOf(length.toLong(), bytes.size - position).toInt()
        System.arraycopy(bytes, position.toInt(), target, offset, n)
        return n
    }
}

fun RandomAccessData.readAll(chunk: Int = 777): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(chunk)
    var at = 0L
    while (true) {
        val n = read(at, buffer, 0, buffer.size)
        if (n < 0) break
        out.write(buffer, 0, n)
        at += n
    }
    return out.toByteArray()
}

class RarArchiveTest {
    private val payload = Random(7).nextBytes(50_000)

    private fun volumes(sets: List<ByteArray>, base: String = "movie") =
        sets.mapIndexed { i, bytes -> ByteVolume("$base.part%02d.rar".format(i + 1), bytes) }

    @Test
    fun `RAR4 stored headers parse with data offsets and split flags`() {
        val sets = RarTestBuilder.rar4("Movie.2024.mkv", payload, perVolume = 20_000)
        val info = RarVolumeParser.parse(ByteVolume("a.rar", sets[1]))
        val entry = info.entries.single()
        assertEquals(4, info.version)
        assertTrue(info.isVolume)
        assertEquals("Movie.2024.mkv", entry.name)
        assertEquals(20_000, entry.packedSize)
        assertEquals(50_000, entry.unpackedSize)
        assertTrue(entry.stored && entry.splitBefore && entry.splitAfter)
        assertEquals(0, RarVolumeParser.parse(ByteVolume("a.rar", sets[0])).volumeNumber)
    }

    @Test
    fun `RAR5 headers carry volume numbers and split flags`() {
        val sets = RarTestBuilder.rar5("Show.S01E02.mkv", payload, perVolume = 20_000)
        val last = RarVolumeParser.parse(ByteVolume("a.rar", sets[2]))
        assertEquals(5, last.version)
        assertEquals(2, last.volumeNumber)
        val entry = last.entries.single()
        assertTrue(entry.splitBefore)
        assertFalse(entry.splitAfter)
        assertEquals(10_000, entry.packedSize)
    }

    @Test
    fun `eager and lazy resolution read the stored entry byte for byte`() {
        for (build in listOf(RarTestBuilder::rar4, RarTestBuilder::rar5)) {
            val sets = build("Movie.mkv", payload, 7_000, false, false, false)
            for (lazy in listOf(false, true)) {
                val set = RarArchive.open(volumes(sets), lazy = lazy, strict = false)
                assertEquals("Movie.mkv", set.entryName)
                assertEquals(payload.size.toLong(), set.data.size())
                assertArrayEquals(payload, set.data.readAll())
            }
        }
    }

    @Test
    fun `lazy resolution parses only the first, second and last volume up front`() {
        val vols = volumes(RarTestBuilder.rar5("Movie.mkv", payload, 5_000))
        val set = RarArchive.open(vols, lazy = true, strict = true)
        assertEquals(listOf(0, 0, 0), vols.subList(3, 6).map { it.headReads.get() })
        // Reading the middle checks its header just before the first byte is served.
        val buffer = ByteArray(10)
        set.data.read(22_000, buffer, 0, 10)
        assertArrayEquals(payload.copyOfRange(22_000, 22_010), buffer)
        assertTrue(vols[4].headReads.get() > 0)
    }

    @Test
    fun `compressed, encrypted and header-encrypted sets are refused`() {
        for (build in listOf(RarTestBuilder::rar4, RarTestBuilder::rar5)) {
            val compressed = assertThrows<ArchiveRefusedException> {
                RarArchive.open(volumes(build("m.mkv", payload, 20_000, true, false, false)), true, false)
            }
            assertTrue(compressed.message.orEmpty().contains("compressed"))
            assertThrows<ArchiveRefusedException> { RarArchive.open(volumes(build("m.mkv", payload, 20_000, false, true, false)), true, false) }
            assertThrows<ArchiveRefusedException> { RarArchive.open(volumes(build("m.mkv", payload, 20_000, false, false, true)), true, false) }
        }
    }

    @Test
    fun `an archive without video is refused`() {
        val set = volumes(RarTestBuilder.rar4("readme.txt", payload, 60_000))
        assertThrows<ArchiveRefusedException> { RarArchive.open(set, lazy = false, strict = false) }
    }

    @Test
    fun `strict membership refuses a stray volume, tolerant mode skips it`() {
        val real = RarTestBuilder.rar4("Movie.mkv", payload, 20_000)
        val stray = RarTestBuilder.rar4("Other.mkv", payload.copyOf(5_000), 20_000).single()
        val mixed = listOf(ByteVolume("a.part1.rar", real[0]), ByteVolume("a.part2.rar", stray), ByteVolume("a.part3.rar", real[1]), ByteVolume("a.part4.rar", real[2]))
        assertThrows<ArchiveRefusedException> { RarArchive.open(mixed, lazy = false, strict = true) }
        assertArrayEquals(payload, RarArchive.open(mixed, lazy = false, strict = false).data.readAll())
    }

    @Test
    fun `a missing volume is refused`() {
        val sets = RarTestBuilder.rar4("Movie.mkv", payload, 10_000)
        val gap = volumes(sets).filterIndexed { i, _ -> i != 2 }
        assertThrows<ArchiveRefusedException> { RarArchive.open(gap, lazy = false, strict = false) }
    }

    @Test
    fun `a lazy layout that turns out wrong re-resolves eagerly`() {
        val sets = RarTestBuilder.rar4("Movie.mkv", payload, 10_000).toMutableList()
        // Volume 3 carries the same bytes behind a RAR5 header: its data starts elsewhere than the lazy guess.
        sets[2] = RarTestBuilder.rar5("Movie.mkv", payload, 10_000)[2]
        val set = RarArchive.open(volumes(sets), lazy = true, strict = false)
        assertTrue(set.data.spans[2].verify != null, "middle volumes start out assumed")
        assertArrayEquals(payload, set.data.readAll())
        assertTrue(set.data.spans.all { it.verify == null }, "the layout was re-resolved eagerly")
    }
}
