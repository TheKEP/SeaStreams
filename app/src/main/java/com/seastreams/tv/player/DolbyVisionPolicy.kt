package com.seastreams.tv.player

import androidx.media3.common.C
import com.seastreams.tv.core.datastore.DolbyVisionPlayback

/** Whether a Dolby Vision track goes to the native DV decoder or plays its HEVC base layer. */
enum class DvDecision { NATIVE, BASE_LAYER }

/** The session-wide inputs of [DolbyVisionPolicy.decide], fixed when playback starts. */
data class DvPolicyInputs(
    val mode: DolbyVisionPlayback,
    val convertProfile7: Boolean,
    val displaySupportsDolbyVision: Boolean,
)

/**
 * Dolby Vision playback rules (PRD §10):
 * - Profile 5 has no compatible base layer, so it is always native.
 * - Profile 7 plays its HDR10 base layer while *Convert Dolby Vision Profile 7 to HDR* is on, or when
 *   the mode is [DolbyVisionPlayback.HDR10_BASE_LAYER].
 * - Profile 8 plays its base layer for [DolbyVisionPlayback.HDR10_BASE_LAYER], or under
 *   [DolbyVisionPlayback.AUTO] when the display doesn't report DV, but only with a known PQ/HLG
 *   transfer: an unknown one may be Profile 8.2's SDR base layer, which must not be tagged as HDR.
 * - Every other profile (AVC/AV1 Dolby Vision) is native.
 */
object DolbyVisionPolicy {

    fun decide(profile: Int?, baseLayerTransfer: Int, inputs: DvPolicyInputs): DvDecision = when (profile) {
        PROFILE_7 -> if (inputs.convertProfile7 || inputs.mode == DolbyVisionPlayback.HDR10_BASE_LAYER) DvDecision.BASE_LAYER else DvDecision.NATIVE
        PROFILE_8 -> if (prefersBaseLayer(inputs) && isHdrTransfer(baseLayerTransfer)) DvDecision.BASE_LAYER else DvDecision.NATIVE
        else -> DvDecision.NATIVE
    }

    private fun prefersBaseLayer(inputs: DvPolicyInputs): Boolean = when (inputs.mode) {
        DolbyVisionPlayback.HDR10_BASE_LAYER -> true
        DolbyVisionPlayback.AUTO -> !inputs.displaySupportsDolbyVision
        DolbyVisionPlayback.NATIVE -> false
    }

    private fun isHdrTransfer(transfer: Int): Boolean = transfer == C.COLOR_TRANSFER_ST2084 || transfer == C.COLOR_TRANSFER_HLG

    const val PROFILE_7 = 7
    const val PROFILE_8 = 8
}
