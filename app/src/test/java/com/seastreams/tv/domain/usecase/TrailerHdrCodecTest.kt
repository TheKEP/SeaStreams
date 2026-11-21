package com.seastreams.tv.domain.usecase

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** PRD §10 SDR-only trailers: HDR YouTube formats would switch the TV into HDR on menu screens. */
class TrailerHdrCodecTest {

    @ParameterizedTest
    @ValueSource(
        strings = [
            "vp09.02.51.10.01.09.16.09.00", // VP9 Profile 2, PQ
            "vp09.02.51.10.01.09.18.09.00", // VP9 Profile 2, HLG
            "vp09.02.51.10", // VP9 Profile 2, no colour fields
            "av01.0.12M.10.0.110.09.16.09.0", // 10-bit AV1, PQ
            "av01.0.12M.10.0.110.09.18.09.0", // 10-bit AV1, HLG
            "av01.0.12M.10", // 10-bit AV1, no colour fields
            "dvh1.08.06", "dvhe.05.06", "dav1.10.06",
        ],
    )
    fun `HDR codecs are recognised`(codec: String) {
        assertTrue(isHdrVideoCodec(codec))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "vp09.00.51.08", "vp9", "vp09.00.51.08.01.01.01.01.00",
            "av01.0.12M.08", "av01.0.12M.08.0.110.01.01.01.0",
            "av01.0.12M.10.0.110.01.01.01.0", // 10-bit but BT.709 transfer: SDR
            "avc1.640028", "hvc1.2.4.L153.B0", "",
        ],
    )
    fun `SDR codecs are not HDR`(codec: String) {
        assertFalse(isHdrVideoCodec(codec))
    }

    @Test
    fun `a missing codec is not HDR`() {
        assertFalse(isHdrVideoCodec(null))
    }

    @Test
    fun `the selector never picks an HDR trailer stream, even when it is the tallest`() {
        val everything = VideoCodecSupport { _, _, _ -> true }
        fun video(height: Int, codec: String) =
            TrailerVideoCandidate("https://v/$height-$codec", height, 30, height * 1_000, codec, TrailerContainer.WEBM, videoOnly = true, dashManifest = null)
        val extracted = ExtractedTrailer(
            videos = listOf(video(2160, "vp09.02.51.10.01.09.16.09.00"), video(2160, "av01.0.12M.10.0.110.09.16.09.0"), video(1440, "vp09.00.51.08")),
            audios = listOf(TrailerAudioCandidate("https://a/128", 128_000, TrailerContainer.M4A, dashManifest = null)),
        )
        assertEquals(1440, TrailerStreamSelector.select(extracted, everything)?.height)
    }
}
