package com.seastreams.tv.player

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import com.seastreams.tv.core.datastore.DolbyVisionPlayback
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** PRD §10 dynamic range detection used by HDR White Dimming and the Info overlay. */
class VideoDynamicRangeTest {

    private fun color(transfer: Int) = ColorInfo.Builder().setColorSpace(C.COLOR_SPACE_BT2020).setColorTransfer(transfer).build()

    private fun format(mime: String?, codecs: String? = null, color: ColorInfo? = null) =
        Format.Builder().setSampleMimeType(mime).setCodecs(codecs).setColorInfo(color).build()

    private val autoInputs = DvPolicyInputs(DolbyVisionPlayback.AUTO, convertProfile7 = true, displaySupportsDolbyVision = false)

    @Test
    fun `the Dolby Vision MIME type is Dolby Vision even without colour info`() {
        assertEquals(DynamicRange.DOLBY_VISION, VideoDynamicRange.of(format(MimeTypes.VIDEO_DOLBY_VISION, "dvhe.05.06")))
        assertEquals(DynamicRange.DOLBY_VISION, VideoDynamicRange.of(format(MimeTypes.VIDEO_DOLBY_VISION)))
    }

    @ParameterizedTest
    @ValueSource(strings = ["dvhe.08.06", "dvh1.05.06", "dvav.09.05", "dva1.09.05", "dav1.10.06", "DVHE.07.06", "hev1.2.4.L153.B0,dvh1.08.06"])
    fun `any Dolby Vision codec prefix is Dolby Vision whatever the MIME type`(codecs: String) {
        assertEquals(DynamicRange.DOLBY_VISION, VideoDynamicRange.of(format(null, codecs)))
        assertEquals(DynamicRange.DOLBY_VISION, VideoDynamicRange.of(format(MimeTypes.VIDEO_H265, codecs)))
    }

    @Test
    fun `PQ is HDR10 (HDR10 and HDR10+), HLG is HLG`() {
        assertEquals(DynamicRange.HDR10, VideoDynamicRange.of(format(MimeTypes.VIDEO_H265, "hvc1.2.4.L153.B0", color(C.COLOR_TRANSFER_ST2084))))
        assertEquals(DynamicRange.HLG, VideoDynamicRange.of(format(MimeTypes.VIDEO_H265, "hvc1.2.4.L153.B0", color(C.COLOR_TRANSFER_HLG))))
        assertEquals(DynamicRange.HDR10, VideoDynamicRange.of(format(MimeTypes.VIDEO_VP9, "vp09.02.51.10", color(C.COLOR_TRANSFER_ST2084))))
    }

    @Test
    fun `no format, no colour info or an SDR transfer is SDR`() {
        assertEquals(DynamicRange.SDR, VideoDynamicRange.of(null))
        assertEquals(DynamicRange.SDR, VideoDynamicRange.of(format(MimeTypes.VIDEO_H264, "avc1.640028")))
        assertEquals(DynamicRange.SDR, VideoDynamicRange.of(format(MimeTypes.VIDEO_H265, "hvc1.1.6.L120", color(C.COLOR_TRANSFER_SDR))))
    }

    @Test
    fun `only SDR is not HDR`() {
        assertFalse(DynamicRange.SDR.isHdr)
        assertTrue(DynamicRange.HDR10.isHdr)
        assertTrue(DynamicRange.HLG.isHdr)
        assertTrue(DynamicRange.DOLBY_VISION.isHdr)
    }

    @Test
    fun `Dolby Vision played as its base layer still reads as Dolby Vision`() {
        val track = format(MimeTypes.VIDEO_DOLBY_VISION, "dvhe.07.06")
        val decoderInput = DolbyVisionCompat.convertIfNeeded(track, autoInputs)
        assertEquals(DynamicRange.HDR10, VideoDynamicRange.of(decoderInput), "the rewritten format alone is HDR10")
        assertEquals(DynamicRange.DOLBY_VISION, VideoDynamicRange.ofSession(track, decoderInput))
    }

    @Test
    fun `the decoder input decides when the track is unknown or SDR-tagged`() {
        val hdr = format(MimeTypes.VIDEO_H265, "hvc1.2.4.L153.B0", color(C.COLOR_TRANSFER_ST2084))
        assertEquals(DynamicRange.HDR10, VideoDynamicRange.ofSession(null, hdr))
        assertEquals(DynamicRange.HDR10, VideoDynamicRange.ofSession(format(MimeTypes.VIDEO_H265), hdr))
        assertEquals(DynamicRange.SDR, VideoDynamicRange.ofSession(null, null))
    }

    @Test
    fun `Info labels show the Dolby Vision path in use`() {
        val pq = color(C.COLOR_TRANSFER_ST2084)
        val p7 = format(MimeTypes.VIDEO_DOLBY_VISION, "dvhe.07.06")
        val p8 = format(MimeTypes.VIDEO_DOLBY_VISION, "dvhe.08.06", pq)
        val p8Hlg = format(MimeTypes.VIDEO_DOLBY_VISION, "dvh1.08.06", color(C.COLOR_TRANSFER_HLG))
        val p5 = format(MimeTypes.VIDEO_DOLBY_VISION, "dvhe.05.06")
        val native = autoInputs.copy(displaySupportsDolbyVision = true)

        assertEquals("Dolby Vision 7 → HDR10", VideoDynamicRange.label(p7, null, autoInputs))
        assertEquals("Dolby Vision 8 → HDR10 base layer", VideoDynamicRange.label(p8, null, autoInputs))
        assertEquals("Dolby Vision 8 → HLG base layer", VideoDynamicRange.label(p8Hlg, null, autoInputs))
        assertEquals("Dolby Vision 8 (native)", VideoDynamicRange.label(p8, null, native))
        assertEquals("Dolby Vision 5 (native)", VideoDynamicRange.label(p5, null, autoInputs))
        assertEquals("HDR10", VideoDynamicRange.label(format(MimeTypes.VIDEO_H265, null, pq), null, autoInputs))
        assertEquals("HLG", VideoDynamicRange.label(null, format(MimeTypes.VIDEO_H265, null, color(C.COLOR_TRANSFER_HLG)), autoInputs))
        assertEquals("SDR", VideoDynamicRange.label(null, null, autoInputs))
    }

    @Test
    fun `the display reports Dolby Vision only when its HDR types include it`() {
        assertTrue(DisplayHdrSupport.reportsDolbyVision(intArrayOf(2, 1, 3)))
        assertFalse(DisplayHdrSupport.reportsDolbyVision(intArrayOf(2, 3, 4)))
        assertFalse(DisplayHdrSupport.reportsDolbyVision(intArrayOf()))
        assertFalse(DisplayHdrSupport.reportsDolbyVision(null))
    }
}
