package com.seastreams.tv.usenet.archive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class MediaSelectionTest {

    @Test
    fun `volume names cover partNN, old rNN with s00 rollover, splits and 7z`() {
        assertEquals(VolumeName("movie", 0, VolumeStyle.RAR_PART), RarVolumeNames.parse("Movie.part01.rar"))
        assertEquals(VolumeName("movie", 11, VolumeStyle.RAR_PART), RarVolumeNames.parse("Movie.part012.rar"))
        assertEquals(VolumeName("movie", 0, VolumeStyle.RAR_OLD), RarVolumeNames.parse("Movie.rar"))
        assertEquals(VolumeName("movie", 1, VolumeStyle.RAR_OLD), RarVolumeNames.parse("Movie.r00"))
        assertEquals(VolumeName("movie", 100, VolumeStyle.RAR_OLD), RarVolumeNames.parse("Movie.r99"))
        assertEquals(VolumeName("movie", 101, VolumeStyle.RAR_OLD), RarVolumeNames.parse("Movie.s00"))
        assertEquals(VolumeName("movie", 801, VolumeStyle.RAR_OLD), RarVolumeNames.parse("movie.z00"))
        assertEquals(VolumeName("movie.mkv", 0, VolumeStyle.SPLIT), RarVolumeNames.parse("Movie.mkv.001"))
        assertEquals(VolumeStyle.SEVEN_ZIP, RarVolumeNames.parse("Movie.7z.002")?.style)
        assertEquals("s00", RarVolumeNames.oldExtension(101))
        assertEquals(null, RarVolumeNames.parse("Movie.mkv"))
    }

    @Test
    fun `the largest group wins and support files are ignored`() {
        val plan = MediaSelector.select(
            listOf(
                ReleaseFile(0, "Movie.nfo", 2_000),
                ReleaseFile(1, "Movie.vol00+01.par2", 900_000_000),
                ReleaseFile(2, "Movie.mkv", 800_000_000),
                ReleaseFile(3, "Movie.srt", 50_000),
            ),
        )
        assertEquals(MediaPlan.Plain(2), plan)
    }

    @Test
    fun `an old-style set with s00 rollover is ordered by volume index`() {
        val files = (0..102).map { i -> ReleaseFile(i, "rel." + RarVolumeNames.oldExtension(102 - i), 50_000_000) }
        val plan = MediaSelector.select(files) as MediaPlan.Rar
        assertEquals((0..102).map { 102 - it }, plan.files)
    }

    @Test
    fun `a sample under 5 percent is excluded, so a 7z main release is refused instead of playing it`() {
        val plan = MediaSelector.select(
            listOf(
                ReleaseFile(0, "Movie.7z.001", 500_000_000),
                ReleaseFile(1, "Movie.7z.002", 500_000_000),
                ReleaseFile(2, "Movie-sample.mkv", 20_000_000),
            ),
        )
        assertTrue(plan is MediaPlan.Refused && plan.reason.contains("7z"))
    }

    @Test
    fun `duplicate volumes collapse to the larger copy and a gap is refused`() {
        val dup = MediaSelector.select(
            listOf(
                ReleaseFile(0, "a.part1.rar", 100),
                ReleaseFile(1, "a.part2.rar", 100),
                ReleaseFile(2, "a.part2.rar", 90),
                ReleaseFile(3, "a.part3.rar", 40),
            ),
        )
        assertEquals(MediaPlan.Rar(listOf(0, 1, 3)), dup)
        val gap = MediaSelector.select(listOf(ReleaseFile(0, "a.part1.rar", 100), ReleaseFile(1, "a.part3.rar", 100)))
        assertTrue(gap is MediaPlan.Refused)
    }

    @Test
    fun `raw splits become a split plan`() {
        val plan = MediaSelector.select((1..3).map { ReleaseFile(it - 1, "Movie.mkv.00$it", 1_000) })
        assertEquals(MediaPlan.Split(listOf(0, 1, 2)), plan)
    }

    private class Probe(
        override val subjectName: String?,
        private val yenc: String,
        private val head: ByteArray,
        private val number: Int? = null,
    ) : NameProbe {
        override fun yencName() = yenc
        override fun head() = head
        override fun rarInfo() = RarVolumeInfo(5, true, number ?: throw IOException("no"), false, emptyList())
    }

    @Test
    fun `obfuscated subjects take the yEnc name, and unnamed RAR volumes are ordered by volume number`() {
        val rar5 = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00)
        val ebml = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 0, 0, 0, 0)
        val names = ObfuscatedSetResolver.resolve(
            listOf(
                Probe("Release.part01.rar", "x", ByteArray(0)),
                Probe("a8f3e2c19b", "Real.Name.part02.rar", ByteArray(0)),
                Probe("q1", "q1", rar5, number = 2),
                Probe("q0", "q0", rar5, number = 0),
                Probe("q2", "q2", rar5, number = 1),
                Probe(null, "zz", ebml),
            ),
        )
        assertEquals("Release.part01.rar", names[0])
        assertEquals("Real.Name.part02.rar", names[1])
        assertEquals(listOf("obfuscated.part003.rar", "obfuscated.part001.rar", "obfuscated.part002.rar"), names.subList(2, 5))
        assertEquals("obfuscated-5.mkv", names[5])
    }
}
