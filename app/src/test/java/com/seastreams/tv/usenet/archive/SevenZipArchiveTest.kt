package com.seastreams.tv.usenet.archive

import com.seastreams.tv.testutil.SevenZipFixtures
import com.seastreams.tv.testutil.SevenZipFixtures.PASSWORD
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException
import kotlin.random.Random

/** Stored, split, password-protected and refused 7z archives (PRD §12, ARCHITECTURE §10 Archives). */
class SevenZipArchiveTest {
    private val payload = SevenZipFixtures.payload()

    private fun single(name: String): JoinedData {
        val volume = ByteVolume(name, SevenZipFixtures.bytes(name))
        return JoinedData(listOf(Span(volume, 0, volume.size())))
    }

    private fun split(): Pair<List<ByteVolume>, JoinedData> {
        val volumes = SevenZipFixtures.SPLIT.map { ByteVolume(it, SevenZipFixtures.bytes(it)) }
        return volumes to JoinedData(volumes.map { Span(it, 0, it.size()) })
    }

    /** Reads at random positions and lengths and compares with the payload. */
    private fun assertRandomReads(data: RandomAccessData) {
        val random = Random(42)
        repeat(200) {
            val position = random.nextLong(payload.size.toLong())
            val length = random.nextInt(1, 9_000)
            val got = data.readUpTo(position, length)
            val expected = payload.copyOfRange(position.toInt(), minOf(payload.size, position.toInt() + length))
            assertArrayEquals(expected, got, "bytes at $position")
        }
        assertEquals(-1, data.read(payload.size.toLong(), ByteArray(4), 0, 4))
    }

    @Test
    fun `a stored 7z exposes the video as an exact slice of the archive`() {
        val set = SevenZipArchive.open(single("stored.7z"), password = null)
        assertEquals(SevenZipFixtures.VIDEO_NAME, set.entryName)
        assertSame(set.data, set.follow, "a Copy entry is read straight from the volumes")
        val span = set.follow.spans.single()
        val archive = SevenZipFixtures.bytes("stored.7z")
        assertArrayEquals(payload, archive.copyOfRange(span.offset.toInt(), (span.offset + span.length).toInt()))
        assertArrayEquals(payload, set.data.readAll())
        assertRandomReads(set.data)
    }

    @Test
    fun `a split 7z set is joined and the entry maps onto exact volume offsets`() {
        val (volumes, joined) = split()
        val set = SevenZipArchive.open(joined, password = null)
        assertEquals(payload.size.toLong(), set.data.size())
        val spans = set.follow.spans
        assertTrue(spans.size >= 3, "the video crosses every volume")
        assertEquals(volumes.map { it as RandomAccessData }, spans.map { it.volume })
        assertEquals(0L, spans[1].offset, "a later volume's part starts at its first byte")
        assertEquals(payload.size.toLong(), spans.sumOf { it.length })
        assertArrayEquals(payload, set.data.readAll(4_096))
        assertRandomReads(set.data)
    }

    @ParameterizedTest
    @ValueSource(strings = ["encrypted-headers.7z", "encrypted-content.7z"])
    fun `a password-protected stored 7z plays with the NZB password`(fixture: String) {
        val set = SevenZipArchive.open(single(fixture), PASSWORD, window = 4_096)
        assertEquals(SevenZipFixtures.VIDEO_NAME, set.entryName)
        assertFalse(set.data === set.follow, "the player reads decrypted bytes")
        assertTrue(set.data is SevenZipAesData)
        assertArrayEquals(payload, set.data.readAll(1_000))
        assertRandomReads(set.data)
        // The default 256 KB window covers the whole small entry.
        assertArrayEquals(payload, SevenZipArchive.open(single(fixture), PASSWORD).data.readAll())
    }

    @ParameterizedTest
    @ValueSource(strings = ["encrypted-headers.7z", "encrypted-content.7z"])
    fun `a password-protected 7z without an NZB password is refused`(fixture: String) {
        val error = assertThrows<ArchiveRefusedException> { SevenZipArchive.open(single(fixture), password = null) }
        assertEquals("The archive is password-protected", error.message)
        assertThrows<ArchiveRefusedException> { SevenZipArchive.open(single(fixture), password = "") }
    }

    @ParameterizedTest
    @ValueSource(strings = ["encrypted-headers.7z", "encrypted-content.7z"])
    fun `a wrong NZB password is refused without leaking it`(fixture: String) {
        val error = assertThrows<ArchiveRefusedException> { SevenZipArchive.open(single(fixture), "not-the-password") }
        assertEquals("The 7z archive password is wrong", error.message)
        assertFalse(error.message.orEmpty().contains("not-the-password"))
    }

    @Test
    fun `a compressed 7z is refused`() {
        val error = assertThrows<ArchiveRefusedException> { SevenZipArchive.open(single("compressed.7z"), password = null) }
        assertEquals("The 7z archive is compressed (only stored archives can stream)", error.message)
    }

    @Test
    fun `a set missing its last volume is refused, not retried`() {
        val (volumes, _) = split()
        assertThrows<ArchiveRefusedException> {
            SevenZipArchive.open(JoinedData(volumes.take(2).map { Span(it, 0, it.size()) }), password = null)
        }
    }

    @Test
    fun `a network failure while reading the directory stays a retryable IOException`() {
        val bytes = SevenZipFixtures.bytes("encrypted-headers.7z")
        val flaky = object : RandomAccessData {
            override fun size(): Long = bytes.size.toLong()
            override fun read(position: Long, target: ByteArray, offset: Int, length: Int): Int {
                if (position >= 32) throw IOException("connection reset")
                val n = minOf(length.toLong(), bytes.size - position).toInt()
                System.arraycopy(bytes, position.toInt(), target, offset, n)
                return n
            }
        }
        val error = assertThrows<IOException> { SevenZipArchive.open(JoinedData(listOf(Span(flaky, 0, flaky.size()))), PASSWORD) }
        assertFalse(error is ArchiveRefusedException)
        assertEquals("connection reset", error.message)
    }

    @Test
    fun `AES parameters above the TV's key-derivation budget are refused`() {
        val properties = byteArrayOf((0xC0 or 24).toByte(), 0x00, 0x01, 0x02)
        assertThrows<ArchiveRefusedException> { SevenZipAesParams.parse(properties) }
        val params = SevenZipAesParams.parse(byteArrayOf((0x40 or 19).toByte(), 0x0F) + ByteArray(16) { it.toByte() })
        assertEquals(19, params.cyclesPower)
        assertEquals(0, params.salt.size)
        assertArrayEquals(ByteArray(16) { it.toByte() }, params.iv)
    }

    @Test
    fun `slices map byte ranges onto volume spans`() {
        val a = ByteVolume("a", ByteArray(100) { it.toByte() })
        val b = ByteVolume("b", ByteArray(100) { (100 + it).toByte() })
        val joined = JoinedData(listOf(Span(a, 10, 80), Span(b, 0, 100)))
        val slice = joined.slice(70, 30)
        assertEquals(listOf(80L to 10L, 0L to 20L), slice.spans.map { it.offset to it.length })
        assertArrayEquals(ByteArray(30) { (if (it < 10) 80 + it else 90 + it).toByte() }, slice.readAll())
        assertThrows<IllegalArgumentException> { joined.slice(170, 20) }
    }
}
