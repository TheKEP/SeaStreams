package com.seastreams.tv.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import java.util.Locale

/** The playing video's dynamic range (PRD §10). HDR10+ is PQ, so it reads as [HDR10]. */
enum class DynamicRange {
    SDR,
    HDR10,
    HLG,
    DOLBY_VISION,
    ;

    val isHdr: Boolean get() = this != SDR
}

/** Classifies video formats for HDR White Dimming and the Info overlay. */
object VideoDynamicRange {

    private val DV_CODEC_PREFIXES = listOf("dvhe.", "dvh1.", "dvav.", "dva1.", "dav1.")

    fun of(format: Format?): DynamicRange {
        if (format == null) return DynamicRange.SDR
        if (isDolbyVision(format)) return DynamicRange.DOLBY_VISION
        return when (format.colorInfo?.colorTransfer) {
            C.COLOR_TRANSFER_ST2084 -> DynamicRange.HDR10
            C.COLOR_TRANSFER_HLG -> DynamicRange.HLG
            else -> DynamicRange.SDR
        }
    }

    /**
     * The session's range: the selected source [track] wins (so Dolby Vision played as its HDR10/HLG
     * base layer still reads as Dolby Vision), else the format the decoder was configured with.
     */
    fun ofSession(track: Format?, decoderInput: Format?): DynamicRange {
        val fromTrack = of(track)
        return if (fromTrack != DynamicRange.SDR) fromTrack else of(decoderInput)
    }

    /**
     * The Info overlay's *Dynamic range* row: `HDR10`, `HLG`, `SDR`, or the Dolby Vision path in use,
     * e.g. `Dolby Vision 8 → HDR10 base layer`, `Dolby Vision 7 → HDR10`, `Dolby Vision 5 (native)`.
     */
    fun label(track: Format?, decoderInput: Format?, inputs: DvPolicyInputs): String {
        val range = ofSession(track, decoderInput)
        if (range != DynamicRange.DOLBY_VISION) {
            return when (range) {
                DynamicRange.HDR10 -> "HDR10"
                DynamicRange.HLG -> "HLG"
                else -> "SDR"
            }
        }
        val source = track?.takeIf { isDolbyVision(it) } ?: decoderInput
        val profile = source?.let { DolbyVisionCompat.profileOf(it.sampleMimeType, it.codecs) }
        val name = if (profile != null) "Dolby Vision $profile" else "Dolby Vision"
        if (source == null || DolbyVisionCompat.decisionFor(source, inputs) != DvDecision.BASE_LAYER) return "$name (native)"
        return when {
            source.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG -> "$name → HLG base layer"
            profile == DolbyVisionPolicy.PROFILE_7 -> "$name → HDR10"
            else -> "$name → HDR10 base layer"
        }
    }

    private fun isDolbyVision(format: Format): Boolean {
        if (format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION) return true
        val codecs = format.codecs ?: return false
        return codecs.split(',').any { part ->
            val codec = part.trim().lowercase(Locale.ROOT)
            DV_CODEC_PREFIXES.any { codec.startsWith(it) }
        }
    }
}
