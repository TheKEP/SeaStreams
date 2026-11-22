package com.seastreams.tv.usenet.yenc

import com.seastreams.tv.testutil.YencTestEncoder
import com.seastreams.tv.usenet.nntp.NntpException
import com.seastreams.tv.usenet.nntp.NntpReader
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.random.Random

class YencDecoderTest {
    private val pool = SegmentBufferPool()
    private val decoder = YencDecoder(pool)

    private fun article(body: ByteArray, trailing: String = ".\r\n"): NntpReader = NntpReader.of(body + trailing.toByteArray())

    private fun decode(body: ByteArray, part: Int? = null, verify: Boolean = true) = decoder.decode(article(body), part, verify)

    @Test
    fun `hand-written vector decodes escapes and the plus-42 shift`() {
        // 'H'(72)+42=114 'r'; 0x00+42='*'; 214+42=0 → "=@"; 227+42=13 (CR) → "=M"; 19+42=61 '=' → "=}"
        val body = "=ybegin line=128 size=5 name=v.bin\r\nr*=@=M=}\r\n=yend size=5 crc32=%08x\r\n"
            .format(YencTestEncoder.crc(byteArrayOf(72, 0, 214.toByte(), 227.toByte(), 19)))
        val segment = decode(body.toByteArray(Charsets.ISO_8859_1))
        assertArrayEquals(byteArrayOf(72, 0, 214.toByte(), 227.toByte(), 19), segment.toByteArray())
        assertEquals("v.bin", segment.name)
        assertEquals(0L, segment.begin)
        assertNull(segment.part)
    }

    @Test
    fun `every byte value round trips, with dot-stuffed lines`() {
        val data = ByteArray(4_096) { (it % 256).toByte() } + ByteArray(300) { ('.'.code - 42).toByte() }
        val body = YencTestEncoder.encode(data, lineLength = 64)
        val segment = decode(body)
        assertArrayEquals(data, segment.toByteArray())
        assertEquals(data.size.toLong(), segment.fileSize)
    }

    @Test
    fun `a multi-part body places bytes by its ypart span`() {
        val data = Random(7).nextBytes(10_000)
        val body = YencTestEncoder.encode(data, 4_000, 7_000, name = "part.bin", part = 5, total = 10)
        val segment = decode(body, part = 5)
        assertEquals(4_000L, segment.begin)
        assertEquals(3_000, segment.length)
        assertEquals(10_000L, segment.fileSize)
        assertArrayEquals(data.copyOfRange(4_000, 7_000), segment.toByteArray())
        assertEquals(5, segment.part)
    }

    @Test
    fun `pcrc32 mismatch fails only when checksums are verified`() {
        val data = Random(1).nextBytes(2_000)
        val body = YencTestEncoder.encode(data, 0, 1_000, part = 1, total = 2, trailerCrc = 0x1234L)
        assertEquals("crc_mismatch", assertThrows<YencException> { decode(body, part = 1) }.code)
        assertArrayEquals(data.copyOfRange(0, 1_000), decode(body, part = 1, verify = false).toByteArray())
    }

    @Test
    fun `single-part crc32 is checked`() {
        val data = Random(2).nextBytes(900)
        val good = YencTestEncoder.encode(data)
        assertArrayEquals(data, decode(good).toByteArray())
        val bad = YencTestEncoder.encode(data, trailerCrc = YencTestEncoder.crc(data) xor 1)
        assertEquals("crc_mismatch", assertThrows<YencException> { decode(bad) }.code)
    }

    @Test
    fun `a whole-file crc32 on a multi-part post is not compared with the part`() {
        val data = Random(3).nextBytes(3_000)
        val body = YencTestEncoder.encode(data, 0, 1_000, part = 1, total = 3, writeCrc = false)
            .toString(Charsets.ISO_8859_1).replace("=yend size=1000 part=1", "=yend size=1000 part=1 crc32=deadbeef")
        assertArrayEquals(data.copyOfRange(0, 1_000), decode(body.toByteArray(Charsets.ISO_8859_1), part = 1).toByteArray())
    }

    @Test
    fun `leading zeros dropped from the crc still match`() {
        val data = Random(4).nextBytes(500)
        val crc = YencTestEncoder.crc(data)
        val body = YencTestEncoder.encode(data).toString(Charsets.ISO_8859_1)
            .replace("crc32=%08x".format(crc), "crc32=" + java.lang.Long.toHexString(crc))
        assertArrayEquals(data, decode(body.toByteArray(Charsets.ISO_8859_1)).toByteArray())
    }

    @Test
    fun `wrong_part when the article is another segment`() {
        val data = Random(5).nextBytes(2_000)
        val body = YencTestEncoder.encode(data, 1_000, 2_000, part = 2, total = 2)
        assertEquals("wrong_part", assertThrows<YencException> { decode(body, part = 3) }.code)
    }

    @Test
    fun `size_mismatch when the ypart span disagrees with the data`() {
        val data = Random(6).nextBytes(2_000)
        val body = YencTestEncoder.encode(data, 0, 1_000, part = 1, total = 2, statedPartSize = 1_200, writeCrc = false)
        assertEquals("size_mismatch", assertThrows<YencException> { decode(body, part = 1) }.code)
    }

    @Test
    fun `overflow, truncated and non-yEnc bodies are refused`() {
        val data = Random(8).nextBytes(1_000)
        val overflow = YencTestEncoder.encode(data, 0, 1_000, part = 1, total = 2, statedPartSize = 500)
        assertEquals("overflow", assertThrows<YencException> { decode(overflow, part = 1) }.code)
        val truncated = YencTestEncoder.encode(data, dropTrailer = true)
        assertEquals("truncated", assertThrows<YencException> { decode(truncated) }.code)
        val plain = assertThrows<YencException> { decode("begin 644 file\r\nM86)C\r\n".toByteArray()) }
        assertEquals("no_yenc", plain.code)
        assertTrue(plain.terminal)
    }

    @Test
    fun `a failed article is read to its terminator so the next answer stays in step`() {
        val data = Random(9).nextBytes(800)
        val bad = YencTestEncoder.encode(data, trailerCrc = 1)
        val reader = NntpReader.of(bad + ".\r\n222 0 <next>\r\n".toByteArray())
        assertThrows<YencException> { decoder.decode(reader, null, true) }
        assertEquals(222, reader.readStatus().code)
    }

    @Test
    fun `released buffers return to the pool and refuse copies`() {
        val data = Random(10).nextBytes(70_000)
        val segment = decode(YencTestEncoder.encode(data))
        segment.release()
        assertTrue(segment.released)
        assertTrue(pool.pooledBytes() >= 70_000)
        assertEquals(false, segment.copyTo(0, ByteArray(10), 0, 10))
        val reused = pool.acquire(70_000)
        assertTrue(reused.size >= 70_000)
    }

    @Test
    fun `a body cut off mid-article returns its buffer to the pool`() {
        val body = YencTestEncoder.encode(Random(3).nextBytes(5_000), lineLength = 128)
        val cut = body.copyOf(body.size / 2)
        assertThrows<NntpException> { decoder.decode(NntpReader.of(cut), null, true) }
        assertTrue(pool.pooledBytes() > 0, "the half-filled buffer went back to the pool")
    }
}
