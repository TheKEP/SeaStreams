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
 * Convert Dolby Vision Profile 7 to HDR (PRD §9). Profile 7 (`dvhe.07` / `dvh1.07`, UHD Blu-ray
 * dual-layer) carries an HDR10-compatible HEVC base layer. Most TV decoders can't decode the
 * enhancement layer and either fail or render a purple/green picture, so when the setting is on the
 * track is presented to the decoder as plain HEVC Main10 (`hev1` / `hvc1`): HEVC decoders ignore the
 * enhancement-layer NAL units and play the HDR10 base layer.
 */
object DolbyVisionCompat {

    private val PROFILE_7_PREFIXES = listOf("dvhe.07", "dvh1.07")

    /** Whether [codecs] (a Format's codecs string, possibly a comma list) is Dolby Vision Profile 7. */
    fun isProfile7(sampleMimeType: String?, codecs: String?): Boolean {
        if (codecs.isNullOrBlank()) return false
        val isDolbyVision = sampleMimeType == null || sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION
        return isDolbyVision && codecs.split(',').any { part ->
            val codec = part.trim().lowercase(Locale.ROOT)
            PROFILE_7_PREFIXES.any { codec.startsWith(it) }
        }
    }

    /**
     * The HEVC codecs string for the base layer: `dvh1` (parameter sets in the sample entry) maps to
     * `hvc1`, `dvhe` (in-band) to `hev1`; Main10 profile, tier/level picked from the DV level.
     */
    fun hevcCodecsFor(dolbyVisionCodecs: String): String {
        val codec = dolbyVisionCodecs.split(',').map { it.trim().lowercase(Locale.ROOT) }
            .firstOrNull { c -> PROFILE_7_PREFIXES.any { c.startsWith(it) } } ?: dolbyVisionCodecs.lowercase(Locale.ROOT)
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

    /** [format] unchanged unless [enabled] and it is Dolby Vision Profile 7. */
    fun convertIfNeeded(format: Format, enabled: Boolean): Format {
        if (!enabled || !isProfile7(format.sampleMimeType, format.codecs)) return format
        val codecs = format.codecs ?: return format
        val color = format.colorInfo ?: ColorInfo.Builder()
            .setColorSpace(C.COLOR_SPACE_BT2020)
            .setColorRange(C.COLOR_RANGE_LIMITED)
            .setColorTransfer(C.COLOR_TRANSFER_ST2084)
            .build()
        return format.buildUpon()
            .setSampleMimeType(MimeTypes.VIDEO_H265)
            .setCodecs(hevcCodecsFor(codecs))
            .setColorInfo(color)
            .build()
    }

    private const val HEVC_LEVEL_4_1 = 123
    private const val HEVC_LEVEL_5_0 = 150
    private const val HEVC_LEVEL_5_1 = 153
    private const val HEVC_LEVEL_5_2 = 156
}

/**
 * ExoPlayer's video renderer with the Profile 7 rewrite applied where the format decides everything:
 * track support (so the track selector picks it), decoder lookup, and the input format the codec is
 * configured with. [convertProfile7] is read on the playback thread whenever a format arrives.
 */
class DolbyVisionCompatVideoRenderer(
    builder: Builder,
    private val convertProfile7: () -> Boolean,
) : MediaCodecVideoRenderer(builder) {
    private fun convert(format: Format): Format = DolbyVisionCompat.convertIfNeeded(format, convertProfile7())

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
