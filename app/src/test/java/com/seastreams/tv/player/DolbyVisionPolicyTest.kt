package com.seastreams.tv.player

import androidx.media3.common.C
import androidx.media3.common.Format
import com.seastreams.tv.core.datastore.DolbyVisionPlayback
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

/** PRD §10 Dolby Vision Playback: which profiles play natively and which as their base layer. */
class DolbyVisionPolicyTest {

    private fun inputs(mode: DolbyVisionPlayback, convertP7: Boolean = true, displayDv: Boolean = false) =
        DvPolicyInputs(mode, convertP7, displayDv)

    @ParameterizedTest
    @EnumSource(DolbyVisionPlayback::class)
    fun `profile 5 is always native`(mode: DolbyVisionPlayback) {
        for (displayDv in listOf(true, false)) {
            assertEquals(DvDecision.NATIVE, DolbyVisionPolicy.decide(5, C.COLOR_TRANSFER_ST2084, inputs(mode, displayDv = displayDv)))
        }
    }

    @ParameterizedTest
    @MethodSource("profile7Cases")
    fun `profile 7 follows the toggle, or the base-layer mode`(mode: DolbyVisionPlayback, convertP7: Boolean, expected: DvDecision) {
        for (displayDv in listOf(true, false)) {
            assertEquals(expected, DolbyVisionPolicy.decide(7, Format.NO_VALUE, inputs(mode, convertP7, displayDv)))
        }
    }

    @ParameterizedTest
    @MethodSource("profile8Cases")
    fun `profile 8 follows the mode, display support and base-layer transfer`(
        mode: DolbyVisionPlayback,
        displayDv: Boolean,
        transfer: Int,
        expected: DvDecision,
    ) {
        assertEquals(expected, DolbyVisionPolicy.decide(8, transfer, inputs(mode, displayDv = displayDv)))
    }

    @Test
    fun `AVC and AV1 Dolby Vision profiles and unknown profiles are native`() {
        val forced = inputs(DolbyVisionPlayback.HDR10_BASE_LAYER)
        for (profile in listOf(9, 10, 4, null)) {
            assertEquals(DvDecision.NATIVE, DolbyVisionPolicy.decide(profile, C.COLOR_TRANSFER_ST2084, forced), "profile $profile")
        }
    }

    companion object {
        @JvmStatic
        fun profile7Cases(): Stream<Arguments> = Stream.of(
            Arguments.of(DolbyVisionPlayback.AUTO, true, DvDecision.BASE_LAYER),
            Arguments.of(DolbyVisionPlayback.AUTO, false, DvDecision.NATIVE),
            Arguments.of(DolbyVisionPlayback.NATIVE, true, DvDecision.BASE_LAYER),
            Arguments.of(DolbyVisionPlayback.NATIVE, false, DvDecision.NATIVE),
            Arguments.of(DolbyVisionPlayback.HDR10_BASE_LAYER, true, DvDecision.BASE_LAYER),
            Arguments.of(DolbyVisionPlayback.HDR10_BASE_LAYER, false, DvDecision.BASE_LAYER),
        )

        @JvmStatic
        fun profile8Cases(): Stream<Arguments> {
            val pq = C.COLOR_TRANSFER_ST2084
            val hlg = C.COLOR_TRANSFER_HLG
            val unknown = Format.NO_VALUE
            val sdr = C.COLOR_TRANSFER_SDR
            return Stream.of(
                Arguments.of(DolbyVisionPlayback.AUTO, false, pq, DvDecision.BASE_LAYER),
                Arguments.of(DolbyVisionPlayback.AUTO, false, hlg, DvDecision.BASE_LAYER),
                Arguments.of(DolbyVisionPlayback.AUTO, false, unknown, DvDecision.NATIVE),
                Arguments.of(DolbyVisionPlayback.AUTO, false, sdr, DvDecision.NATIVE),
                Arguments.of(DolbyVisionPlayback.AUTO, true, pq, DvDecision.NATIVE),
                Arguments.of(DolbyVisionPlayback.NATIVE, false, pq, DvDecision.NATIVE),
                Arguments.of(DolbyVisionPlayback.NATIVE, true, pq, DvDecision.NATIVE),
                Arguments.of(DolbyVisionPlayback.HDR10_BASE_LAYER, true, pq, DvDecision.BASE_LAYER),
                Arguments.of(DolbyVisionPlayback.HDR10_BASE_LAYER, false, hlg, DvDecision.BASE_LAYER),
                Arguments.of(DolbyVisionPlayback.HDR10_BASE_LAYER, true, unknown, DvDecision.NATIVE),
            )
        }
    }
}
