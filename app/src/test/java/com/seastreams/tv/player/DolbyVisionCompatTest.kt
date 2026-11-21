package com.seastreams.tv.player

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import com.seastreams.tv.core.datastore.DolbyVisionPlayback
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class DolbyVisionCompatTest {

    private val pq = ColorInfo.Builder().setColorSpace(C.COLOR_SPACE_BT2020).setColorTransfer(C.COLOR_TRANSFER_ST2084).build()
    private val hlg = ColorInfo.Builder().setColorSpace(C.COLOR_SPACE_BT2020).setColorTransfer(C.COLOR_TRANSFER_HLG).build()

    private fun dvFormat(codecs: String, color: ColorInfo? = null): Format = Format.Builder()
        .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
        .setCodecs(codecs)
        .setWidth(3840)
        .setHeight(2160)
        .setColorInfo(color)
        .build()

    private fun inputs(
        mode: DolbyVisionPlayback = DolbyVisionPlayback.AUTO,
        convertP7: Boolean = true,
        displayDv: Boolean = false,
    ) = DvPolicyInputs(mode, convertP7, displayDv)

    @ParameterizedTest
    @ValueSource(strings = ["dvhe.07.06", "dvh1.07.06", "DVHE.07.09", "dvhe.07.06,hev1.2.4.L153"])
    fun `profile 7 codecs are recognised`(codecs: String) {
        assertTrue(DolbyVisionCompat.isProfile7(MimeTypes.VIDEO_DOLBY_VISION, codecs))
    }

    @ParameterizedTest
    @ValueSource(strings = ["dvhe.08.06", "dvh1.05.06", "dvav.09.05", "hev1.2.4.L153.B0", ""])
    fun `other profiles and plain HEVC are not profile 7`(codecs: String) {
        assertFalse(DolbyVisionCompat.isProfile7(MimeTypes.VIDEO_DOLBY_VISION, codecs))
    }

    @ParameterizedTest
    @CsvSource("dvhe.05.06, 5", "dvh1.07.06, 7", "dvhe.08.09, 8", "dvav.09.05, 9", "dav1.10.06, 10", "'hev1.2.4.L153.B0,dvhe.08.06', 8")
    fun `the profile is read from any Dolby Vision codec in the list`(codecs: String, profile: Int) {
        assertEquals(profile, DolbyVisionCompat.profileOf(MimeTypes.VIDEO_DOLBY_VISION, codecs))
        assertEquals(profile, DolbyVisionCompat.profileOf(null, codecs), "missing MIME type still parses")
    }

    @Test
    fun `non Dolby Vision MIME types and codecs have no profile`() {
        assertNull(DolbyVisionCompat.profileOf(MimeTypes.VIDEO_H265, "dvhe.07.06"))
        assertNull(DolbyVisionCompat.profileOf(MimeTypes.VIDEO_DOLBY_VISION, "hev1.2.4.L153.B0"))
        assertNull(DolbyVisionCompat.profileOf(MimeTypes.VIDEO_DOLBY_VISION, null))
    }

    @ParameterizedTest
    @CsvSource(
        "dvhe.07.06, hev1.2.4.L150.B0", "dvh1.07.06, hvc1.2.4.L150.B0", "dvhe.07.09, hev1.2.4.L153.B0", "dvhe.07.03, hev1.2.4.L123.B0",
        "dvhe.08.06, hev1.2.4.L150.B0", "dvh1.08.09, hvc1.2.4.L153.B0", "dvh1.08.10, hvc1.2.4.L156.B0",
    )
    fun `base layer codecs keep the sample entry type and map the level`(dv: String, hevc: String) {
        assertEquals(hevc, DolbyVisionCompat.hevcCodecsFor(dv))
    }

    @Test
    fun `profile 7 with the toggle on falls back to the HDR10 HEVC base layer`() {
        val converted = DolbyVisionCompat.convertIfNeeded(dvFormat("dvhe.07.06"), inputs())

        assertEquals(MimeTypes.VIDEO_H265, converted.sampleMimeType)
        assertEquals("hev1.2.4.L150.B0", converted.codecs)
        assertEquals(3840, converted.width)
        assertEquals(C.COLOR_TRANSFER_ST2084, converted.colorInfo?.colorTransfer, "base layer is HDR10 (PQ)")
        assertEquals(C.COLOR_SPACE_BT2020, converted.colorInfo?.colorSpace)
    }

    @Test
    fun `profile 7 keeps the stream's own colour info`() {
        val converted = DolbyVisionCompat.convertIfNeeded(dvFormat("dvh1.07.06", hlg), inputs())
        assertEquals(C.COLOR_TRANSFER_HLG, converted.colorInfo?.colorTransfer)
        assertEquals("hvc1.2.4.L150.B0", converted.codecs)
    }

    @Test
    fun `profile 7 with the toggle off stays Dolby Vision unless the mode forces the base layer`() {
        val format = dvFormat("dvhe.07.06")
        assertSame(format, DolbyVisionCompat.convertIfNeeded(format, inputs(convertP7 = false)))
        assertEquals(MimeTypes.VIDEO_H265, DolbyVisionCompat.convertIfNeeded(format, inputs(DolbyVisionPlayback.HDR10_BASE_LAYER, convertP7 = false)).sampleMimeType)
    }

    @Test
    fun `profile 8 base layer keeps the container ColorInfo including HDR static info`() {
        val withStatic = pq.buildUpon().setHdrStaticInfo(ByteArray(25) { it.toByte() }).build()
        val converted = DolbyVisionCompat.convertIfNeeded(dvFormat("dvhe.08.06", withStatic), inputs(DolbyVisionPlayback.HDR10_BASE_LAYER))

        assertEquals(MimeTypes.VIDEO_H265, converted.sampleMimeType)
        assertEquals("hev1.2.4.L150.B0", converted.codecs)
        assertEquals(withStatic, converted.colorInfo)
    }

    @Test
    fun `profile 8 HLG base layer plays as HLG`() {
        val converted = DolbyVisionCompat.convertIfNeeded(dvFormat("dvh1.08.06", hlg), inputs())
        assertEquals(C.COLOR_TRANSFER_HLG, converted.colorInfo?.colorTransfer)
        assertEquals(MimeTypes.VIDEO_H265, converted.sampleMimeType)
    }

    @Test
    fun `profile 8 without ColorInfo is never given an invented one`() {
        val format = dvFormat("dvhe.08.06")
        assertSame(format, DolbyVisionCompat.convertIfNeeded(format, inputs(DolbyVisionPlayback.HDR10_BASE_LAYER)))
    }

    @Test
    fun `profile 8 stays native when the display reports Dolby Vision under Auto, or when forced native`() {
        val format = dvFormat("dvhe.08.06", pq)
        assertSame(format, DolbyVisionCompat.convertIfNeeded(format, inputs(displayDv = true)))
        assertSame(format, DolbyVisionCompat.convertIfNeeded(format, inputs(DolbyVisionPlayback.NATIVE)))
    }

    @Test
    fun `profile 5 and plain HEVC are untouched`() {
        val profile5 = dvFormat("dvhe.05.06", pq)
        val hevc = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).setCodecs("hvc1.2.4.L153.B0").setColorInfo(pq).build()
        assertSame(profile5, DolbyVisionCompat.convertIfNeeded(profile5, inputs(DolbyVisionPlayback.HDR10_BASE_LAYER)))
        assertSame(hevc, DolbyVisionCompat.convertIfNeeded(hevc, inputs(DolbyVisionPlayback.HDR10_BASE_LAYER)))
    }
}
