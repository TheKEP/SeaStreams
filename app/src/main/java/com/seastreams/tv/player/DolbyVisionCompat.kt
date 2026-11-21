package com.seastreams.tv.player

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import java.util.Locale

/**
 * Dolby Vision base-layer playback (PRD §10). Profile 7 (`dvhe.07` / `dvh1.07`, UHD Blu-ray dual-layer)
 * and Profile 8 (`dvhe.08` / `dvh1.08`) carry an HEVC Main10 base layer. When [DolbyVisionPolicy]
 * picks [DvDecision.BASE_LAYER], the track is presented to the decoder as plain HEVC (`hev1` / `hvc1`):
 * HEVC decoders ignore the Dolby Vision RPU / enhancement-layer NAL units and play the HDR10 (or HLG)
 * base layer with the stream's own colour and mastering metadata.
 */
object DolbyVisionCompat {

    private val DV_CODEC_PREFIXES = listOf("dvhe.", "dvh1.", "dvav.", "dva1.", "dav1.")
    private val HEVC_DV_PREFIXES = listOf("dvhe.", "dvh1.")

    /** The Dolby Vision profile in [codecs] (a Format's codecs string, possibly a comma list), or `null`. */
    fun profileOf(sampleMimeType: String?, codecs: String?): Int? {
        if (codecs.isNullOrBlank()) return null
        if (sampleMimeType != null && sampleMimeType != MimeTypes.VIDEO_DOLBY_VISION) return null
        return codecs.split(',').firstNotNullOfOrNull { part ->
            val codec = part.trim().lowercase(Locale.ROOT)
            if (DV_CODEC_PREFIXES.none { codec.startsWith(it) }) null else codec.split('.').getOrNull(1)?.toIntOrNull()
        }
    }

    fun isProfile7(sampleMimeType: String?, codecs: String?): Boolean = profileOf(sampleMimeType, codecs) == DolbyVisionPolicy.PROFILE_7

    /**
     * The HEVC codecs string for the base layer: `dvh1` (parameter sets in the sample entry) maps to
     * `hvc1`, `dvhe` (in-band) to `hev1`; Main10 profile, tier/level picked from the DV level.
     */
    fun hevcCodecsFor(dolbyVisionCodecs: String): String {
        val codec = dolbyVisionCodecs.split(',').map { it.trim().lowercase(Locale.ROOT) }
            .firstOrNull { c -> HEVC_DV_PREFIXES.any { c.startsWith(it) } } ?: dolbyVisionCodecs.lowercase(Locale.ROOT)
        val sampleEntry = if (codec.startsWith("dvh1")) "hvc1" else "hev1"
        val dvLevel = codec.split('.').getOrNull(2)?.toIntOrNull() ?: 0
        return "$sampleEntry.2.4.L${hevcLevelFor(dvLevel)}.B0"
    }

    /** HEVC `general_level_idc` (30 × level) able to carry a Dolby Vision level's resolution/frame rate. */
    internal fun hevcLevelFor(dolbyVisionLevel: Int): Int = when {
        dolbyVisionLevel in 1..3 -> HEVC_LEVEL_4_1 // up to 1080p
        dolbyVisionLevel in 4..6 -> HEVC_LEVEL_5_0 // 4K up to 30 fps
        dolbyVisionLevel in 7..9 -> HEVC_LEVEL_5_1 // 4K up to 60 fps
        dolbyVisionLevel >= 10 -> HEVC_LEVEL_5_2 // 4K 120 fps / 8K
        else -> HEVC_LEVEL_5_1
    }

    /** What [DolbyVisionPolicy] decides for [format] (`NATIVE` for anything that isn't Dolby Vision). */
    fun decisionFor(format: Format, inputs: DvPolicyInputs): DvDecision {
        val profile = profileOf(format.sampleMimeType, format.codecs) ?: return DvDecision.NATIVE
        return DolbyVisionPolicy.decide(profile, format.colorInfo?.colorTransfer ?: Format.NO_VALUE, inputs)
    }

    /** [format] unchanged unless the policy plays it as its HEVC base layer. */
    fun convertIfNeeded(format: Format, inputs: DvPolicyInputs): Format {
        if (decisionFor(format, inputs) != DvDecision.BASE_LAYER) return format
        val codecs = format.codecs ?: return format
        val profile = profileOf(format.sampleMimeType, codecs)
        // Only Profile 7's base layer is always HDR10; Profile 8 reaches here only with a known PQ/HLG ColorInfo.
        val color = format.colorInfo ?: if (profile == DolbyVisionPolicy.PROFILE_7) PROFILE_7_BASE_LAYER_COLOR else return format
        return format.buildUpon()
            .setSampleMimeType(MimeTypes.VIDEO_H265)
            .setCodecs(hevcCodecsFor(codecs))
            .setColorInfo(color)
            .build()
    }

    private val PROFILE_7_BASE_LAYER_COLOR: ColorInfo = ColorInfo.Builder()
        .setColorSpace(C.COLOR_SPACE_BT2020)
        .setColorRange(C.COLOR_RANGE_LIMITED)
        .setColorTransfer(C.COLOR_TRANSFER_ST2084)
        .build()

    private const val HEVC_LEVEL_4_1 = 123
    private const val HEVC_LEVEL_5_0 = 150
    private const val HEVC_LEVEL_5_1 = 153
    private const val HEVC_LEVEL_5_2 = 156
}

/**
 * ExoPlayer's video renderer with the base-layer rewrite applied where the format decides everything:
 * track support (so the track selector picks it), decoder lookup, and the input format the codec is
 * configured with. [inputs] is read on the playback thread whenever a format arrives.
 */
class DolbyVisionCompatVideoRenderer(
    builder: Builder,
    private val inputs: () -> DvPolicyInputs,
) : MediaCodecVideoRenderer(builder) {
    private fun convert(format: Format): Format = DolbyVisionCompat.convertIfNeeded(format, inputs())

    override fun supportsFormat(mediaCodecSelector: MediaCodecSelector, format: Format): Int =
        super.supportsFormat(mediaCodecSelector, convert(format))

    override fun getDecoderInfos(
        mediaCodecSelector: MediaCodecSelector,
        format: Format,
        requiresSecureDecoder: Boolean,
    ): List<MediaCodecInfo> = super.getDecoderInfos(mediaCodecSelector, convert(format), requiresSecureDecoder)

    override fun onInputFormatChanged(formatHolder: FormatHolder): DecoderReuseEvaluation? {
        formatHolder.format = formatHolder.format?.let(::convert)
        return super.onInputFormatChanged(formatHolder)
    }
}
