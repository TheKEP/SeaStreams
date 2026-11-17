package com.seastreams.tv.player

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class DolbyVisionCompatTest {

    private fun dvFormat(codecs: String, color: ColorInfo? = null): Format = Format.Builder()
        .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
        .setCodecs(codecs)
        .setWidth(3840)
        .setHeight(2160)
        .setColorInfo(color)
        .build()

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

    @Test
    fun `a non Dolby Vision mime type is never treated as profile 7`() {
        assertFalse(DolbyVisionCompat.isProfile7(MimeTypes.VIDEO_H265, "dvhe.07.06"))
    }

    @ParameterizedTest
    @CsvSource("dvhe.07.06, hev1.2.4.L150.B0", "dvh1.07.06, hvc1.2.4.L150.B0", "dvhe.07.09, hev1.2.4.L153.B0", "dvhe.07.03, hev1.2.4.L123.B0")
    fun `base layer codecs keep the sample entry type and map the level`(dv: String, hevc: String) {
        assertEquals(hevc, DolbyVisionCompat.hevcCodecsFor(dv))
    }

    @Test
    fun `enabled - profile 7 falls back to the HDR10 HEVC base layer`() {
        val converted = DolbyVisionCompat.convertIfNeeded(dvFormat("dvhe.07.06"), enabled = true)

        assertEquals(MimeTypes.VIDEO_H265, converted.sampleMimeType)
        assertEquals("hev1.2.4.L150.B0", converted.codecs)
        assertEquals(3840, converted.width)
        val color = converted.colorInfo
        assertEquals(C.COLOR_TRANSFER_ST2084, color?.colorTransfer, "base layer is HDR10 (PQ)")
        assertEquals(C.COLOR_SPACE_BT2020, color?.colorSpace)
    }

    @Test
    fun `enabled - the stream's own colour info is kept`() {
        val hlg = ColorInfo.Builder().setColorSpace(C.COLOR_SPACE_BT2020).setColorTransfer(C.COLOR_TRANSFER_HLG).build()
        val converted = DolbyVisionCompat.convertIfNeeded(dvFormat("dvh1.07.06", hlg), enabled = true)
        assertEquals(C.COLOR_TRANSFER_HLG, converted.colorInfo?.colorTransfer)
        assertEquals("hvc1.2.4.L150.B0", converted.codecs)
    }

    @Test
    fun `disabled - profile 7 is left as Dolby Vision`() {
        val format = dvFormat("dvhe.07.06")
        assertSame(format, DolbyVisionCompat.convertIfNeeded(format, enabled = false))
    }

    @Test
    fun `enabled - profile 8 and plain HEVC are untouched`() {
        val profile8 = dvFormat("dvhe.08.06")
        val hevc = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).setCodecs("hvc1.2.4.L153.B0").build()
        assertSame(profile8, DolbyVisionCompat.convertIfNeeded(profile8, enabled = true))
        assertSame(hevc, DolbyVisionCompat.convertIfNeeded(hevc, enabled = true))
    }
}
