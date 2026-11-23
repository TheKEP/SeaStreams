package com.seastreams.tv.usenet.verify

import com.seastreams.tv.domain.usenet.DamagePolicy
import com.seastreams.tv.usenet.nntp.ArticleNotFoundException
import com.seastreams.tv.usenet.nntp.FetchPriority
import com.seastreams.tv.usenet.nntp.NntpFuture
import com.seastreams.tv.usenet.nntp.SegmentFetch
import com.seastreams.tv.usenet.nntp.SegmentSource
import com.seastreams.tv.usenet.nzb.NzbFile
import com.seastreams.tv.usenet.nzb.NzbSegment
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.atomic.AtomicInteger

private fun CensusVerifier.Census.finished(): CensusReport = checkNotNull(await(1_000))

class VerificationTest {

    private fun file(prefix: String, count: Int) =
        NzbFile("\"$prefix.mkv\" yEnc", "p", listOf("g"), (1..count).map { NzbSegment(it, 700_000, "$prefix-$it@x") })

    /** STAT answered at once: `false` for [missing] IDs, a failure for [unknown] ones. */
    private fun stat(missing: Set<String>, unknown: Set<String> = emptySet(), asked: AtomicInteger = AtomicInteger()) = { id: String ->
        asked.incrementAndGet()
        NntpFuture<Boolean>().apply {
            if (id in unknown) fail(java.io.IOException("timeout")) else complete(id !in missing)
        }
    }

    @Test
    fun `strict census calls one missing article broken and stops early`() {
        val asked = AtomicInteger()
        val files = listOf(file("a", 400))
        val census = CensusVerifier(stat(setOf("a-200@x"), asked = asked), 8, 60_000, DamagePolicy.STRICT).start(files)
        val report = census.finished()
        assertTrue(report.verdict is DamageVerdict.Broken)
        assertTrue(asked.get() < 400, "stops at the first missing article")
    }

    @Test
    fun `tolerant census accepts holes within the limits`() {
        val files = listOf(file("a", 400))
        val missing = setOf("a-10@x", "a-11@x", "a-200@x")
        val report = CensusVerifier(stat(missing), 8, 60_000, DamagePolicy.TOLERANT).start(files).finished()
        assertEquals(DamageVerdict.Repairable(3), report.verdict)
        assertTrue(report.complete)
        assertEquals(mapOf(0 to listOf(9, 10, 199)), report.missing)
    }

    @Test
    fun `tolerant census refuses five in a row, a missing first article, and over two percent`() {
        val files = listOf(file("a", 400))
        val run = (20..24).map { "a-$it@x" }.toSet()
        assertTrue(CensusVerifier(stat(run), 8, 60_000, DamagePolicy.TOLERANT).start(files).finished().verdict is DamageVerdict.Broken)
        assertTrue(CensusVerifier(stat(setOf("a-1@x")), 8, 60_000, DamagePolicy.TOLERANT).start(files).finished().verdict is DamageVerdict.Broken)
        val spread = (1..9).map { "a-${it * 40}@x" }.toSet() // 9 of 400 = 2.25%
        assertTrue(CensusVerifier(stat(spread), 8, 60_000, DamagePolicy.TOLERANT).start(files).finished().verdict is DamageVerdict.Broken)
    }

    @Test
    fun `a STAT that fails is unknown, never missing`() {
        val report = CensusVerifier(stat(emptySet(), unknown = setOf("a-3@x")), 4, 60_000, DamagePolicy.STRICT).start(listOf(file("a", 10))).finished()
        assertEquals(DamageVerdict.Intact, report.verdict)
    }

    @Test
    fun `census runs at most the configured concurrency and honours its lifetime`() {
        val pending = ArrayList<NntpFuture<Boolean>>()
        var now = 0L
        val census = CensusVerifier({ NntpFuture<Boolean>().also { pending += it } }, 3, 1_000, DamagePolicy.STRICT, clock = { now }).start(listOf(file("a", 50)))
        assertEquals(3, pending.size)
        pending[0].complete(true)
        assertEquals(4, pending.size)
        now = 2_000
        pending[1].complete(true)
        val report = checkNotNull(census.await(0))
        assertFalse(report.complete)
    }

    @Test
    fun `hole limits are four in a row, sixty-four in all, two percent`() {
        assertTrue(HoleFiller.withinLimits(mapOf(0 to listOf(5, 6, 7, 8)), 1_000))
        assertFalse(HoleFiller.withinLimits(mapOf(0 to listOf(5, 6, 7, 8, 9)), 1_000))
        assertTrue(HoleFiller.withinLimits(mapOf(0 to (0 until 64).map { it * 10 }), 10_000))
        assertFalse(HoleFiller.withinLimits(mapOf(0 to (0 until 65).map { it * 10 }), 10_000))
        assertFalse(HoleFiller.withinLimits(mapOf(0 to listOf(10, 20, 30)), 100))
        assertEquals(3, HoleFiller.longestRun(listOf(9, 1, 2, 3, 7)))
    }

    @Test
    fun `Matroska holes become one EBML Void element of exactly the hole size`() {
        val big = MatroskaHoleRepair.void(700_000)
        assertEquals(700_000, big.size)
        assertEquals(0xEC.toByte(), big[0])
        assertEquals(0x01.toByte(), big[1]) // 8-byte size vint marker
        val payload = 700_000L - 9
        val size = (2..8).fold(0L) { acc, i -> (acc shl 8) or (big[i].toLong() and 0xFF) }
        assertEquals(payload, size)
        assertTrue(big.drop(9).all { it == 0.toByte() })
        assertArrayEquals(byteArrayOf(0xEC.toByte(), 0x80.toByte()), MatroskaHoleRepair.void(2))
        assertArrayEquals(byteArrayOf(0xEC.toByte(), 0x40, 0x00), MatroskaHoleRepair.void(3))
        assertArrayEquals(ByteArray(5), HoleFiller.filler(5, matroska = false))
    }

    @Test
    fun `the hole-filling source fills a missing article under Tolerant and fails under Strict`() {
        val missing = SegmentSource { id, _, _ -> SegmentFetch().apply { fail(ArticleNotFoundException(id, true)) } }
        val slot = { _: String -> HoleSlot(0, 3, 3_000, 1_000, 10_000, "a.mkv", 10) }
        val tolerant = HoleFillingSource(missing, DamagePolicy.TOLERANT, HoleBudget(1_000), slot, matroska = { true })
        val filled = tolerant.fetch("x@y", 4, FetchPriority.HIGH).await(1_000)
        assertEquals(3_000L, filled.begin)
        assertEquals(1_000, filled.length)
        assertEquals(0xEC.toByte(), checkNotNull(filled.toByteArray())[0])
        val strict = HoleFillingSource(missing, DamagePolicy.STRICT, HoleBudget(1_000), slot, matroska = { true })
        assertThrows<ArticleNotFoundException> { strict.fetch("x@y", 4, FetchPriority.HIGH).await(1_000) }
        val exhausted = HoleBudget(10) // 1 hole of 10 articles = 10% > 2%
        assertThrows<ArticleNotFoundException> {
            HoleFillingSource(missing, DamagePolicy.TOLERANT, exhausted, slot, matroska = { false }).fetch("x@y", 4, FetchPriority.HIGH).await(1_000)
        }
    }

    @Test
    fun `content sniffing recognises containers`() {
        assertEquals(MediaContainer.MATROSKA, ContentSniffer.sniff(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 0x42, 0x82.toByte(), 0x88.toByte()) + "matroska".toByteArray()))
        assertEquals(MediaContainer.WEBM, ContentSniffer.sniff(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 0x42, 0x82.toByte(), 0x84.toByte()) + "webm".toByteArray()))
        assertEquals(MediaContainer.MP4, ContentSniffer.sniff(byteArrayOf(0, 0, 0, 0x20) + "ftypisom".toByteArray()))
        val ts = ByteArray(188 * 3 + 1).also { b -> (0..2).forEach { b[it * 188] = 0x47 } }
        assertEquals(MediaContainer.MPEG_TS, ContentSniffer.sniff(ts))
        assertEquals(MediaContainer.UNKNOWN, ContentSniffer.sniff("MZ executable".toByteArray()))
    }
}
