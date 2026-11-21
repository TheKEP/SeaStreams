package com.seastreams.tv.domain.usecase

import com.seastreams.tv.domain.model.TrailerSource
import com.seastreams.tv.domain.model.TrailerStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale
import javax.inject.Inject

/** Container of an extracted YouTube stream. */
enum class TrailerContainer { MP4, WEBM, M4A, WEBMA, OTHER }

/** One video stream the extractor found. [videoOnly] streams are adaptive (no audio). */
data class TrailerVideoCandidate(
    val url: String,
    val height: Int,
    val fps: Int,
    val bitrate: Int,
    /** RFC 6381 codec, e.g. `avc1.640028`, `vp09.00.51.08`, `av01.0.12M.08`. */
    val codec: String?,
    val container: TrailerContainer,
    val videoOnly: Boolean,
    val dashManifest: String?,
)

data class TrailerAudioCandidate(
    val url: String,
    val bitrate: Int,
    val container: TrailerContainer,
    val dashManifest: String?,
)

/** Everything the extractor returned for one YouTube video. */
data class ExtractedTrailer(val videos: List<TrailerVideoCandidate>, val audios: List<TrailerAudioCandidate>)

/** Runs YouTube stream extraction for a video id (NewPipeExtractor in production). */
fun interface TrailerExtractor {
    @Throws(IOException::class)
    suspend fun extract(youtubeKey: String): ExtractedTrailer
}

/** Whether this device has a decoder for a codec (avc, vp9, av1 …) at a 16:9 [height] and [fps]. */
fun interface VideoCodecSupport {
    fun isSupported(codec: String?, height: Int, fps: Int): Boolean
}

class NoTrailerStreamException(key: String) : IOException("No playable trailer stream for $key")

/**
 * PRD §9 trailer streams: the highest-quality adaptive video stream at or below 2160p that the device
 * can decode (taller first, then higher frame rate, then MP4 over WebM, then bitrate), paired with the
 * best M4A audio stream. When YouTube offers no adaptive pair, the best muxed stream is used instead.
 */
object TrailerStreamSelector {
    const val MAX_HEIGHT = 2160

    fun select(extracted: ExtractedTrailer, codecs: VideoCodecSupport, maxHeight: Int = MAX_HEIGHT): TrailerStream? {
        val audio = extracted.audios.filter { it.container == TrailerContainer.M4A }.maxByOrNull { it.bitrate }
        val adaptive = extracted.videos.filter { it.videoOnly && it.height in 1..maxHeight && codecs.decodes(it) }
        val video = adaptive.maxWithOrNull(VIDEO_ORDER)
        if (video != null && audio != null) {
            return TrailerStream(
                video = TrailerSource(video.url, video.dashManifest, video.container.mimeType()),
                audio = TrailerSource(audio.url, audio.dashManifest, audio.container.mimeType()),
                height = video.height,
            )
        }
        val muxed = extracted.videos.filter { !it.videoOnly && it.height <= maxHeight && codecs.decodes(it) }
            .maxWithOrNull(VIDEO_ORDER) ?: return null
        return TrailerStream(TrailerSource(muxed.url, muxed.dashManifest, muxed.container.mimeType()), audio = null, height = muxed.height)
    }

    /** SDR only (PRD §10): an HDR trailer would switch the TV into HDR on Details/Home. */
    private fun VideoCodecSupport.decodes(video: TrailerVideoCandidate) =
        !isHdrVideoCodec(video.codec) && isSupported(video.codec, video.height, video.fps)

    private val VIDEO_ORDER: Comparator<TrailerVideoCandidate> = compareBy<TrailerVideoCandidate> { it.height }
        .thenBy { it.fps }
        .thenBy { if (it.container == TrailerContainer.MP4) 1 else 0 }
        .thenBy { it.bitrate }

    private fun TrailerContainer.mimeType(): String? = when (this) {
        TrailerContainer.MP4 -> "video/mp4"
        TrailerContainer.WEBM -> "video/webm"
        TrailerContainer.M4A -> "audio/mp4"
        TrailerContainer.WEBMA -> "audio/webm"
        TrailerContainer.OTHER -> null
    }
}

/** Maps an RFC 6381 codec string to its family, e.g. `avc1.640028` → `avc`. */
fun videoCodecFamily(codec: String?): String? {
    val value = codec?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
    return when {
        value.startsWith("avc") -> "avc"
        value.startsWith("vp9") || value.startsWith("vp09") -> "vp9"
        value.startsWith("av01") -> "av1"
        value.startsWith("hev") || value.startsWith("hvc") -> "hevc"
        value.startsWith("vp8") -> "vp8"
        else -> value.substringBefore('.')
    }
}

/**
 * Whether an RFC 6381 video codec string is HDR: any Dolby Vision codec; VP9 / AV1 whose transfer
 * characteristics field is PQ (`16`) or HLG (`18`); without that field, VP9 Profile 2 or 10/12-bit AV1
 * (YouTube's HDR formats; its SDR VP9/AV1 are Profile 0 / 8-bit).
 */
fun isHdrVideoCodec(codec: String?): Boolean {
    val value = codec?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return false
    if (DV_CODECS.any { value.startsWith(it) }) return true
    val parts = value.split('.')
    return when (parts.first()) {
        // vp09.PP.LL.DD.CC.cp.tc.mc.FF
        "vp09" -> parts.getOrNull(VP9_TRANSFER)?.toIntOrNull()?.let { it in HDR_TRANSFERS }
            ?: (parts.getOrNull(1)?.toIntOrNull() == VP9_PROFILE_2)
        // av01.P.LLT.DD.M.CCC.cp.tc.mc.F
        "av01" -> parts.getOrNull(AV1_TRANSFER)?.toIntOrNull()?.let { it in HDR_TRANSFERS }
            ?: ((parts.getOrNull(AV1_BIT_DEPTH)?.toIntOrNull() ?: SDR_BIT_DEPTH) > SDR_BIT_DEPTH)
        else -> false
    }
}

private val DV_CODECS = listOf("dvhe", "dvh1", "dvav", "dva1", "dav1")
private val HDR_TRANSFERS = setOf(16, 18)
private const val VP9_TRANSFER = 6
private const val AV1_TRANSFER = 7
private const val AV1_BIT_DEPTH = 3
private const val SDR_BIT_DEPTH = 8
private const val VP9_PROFILE_2 = 2

/**
 * Resolves the YouTube trailer key TMDB lists for a title into playable streams, off the main thread.
 * Throws when extraction fails or nothing playable is found; callers decide the fallback (Details
 * opens YouTube, catalog rows silently keep their artwork).
 */
class ResolveTrailerStreamUseCase(
    private val extractor: TrailerExtractor,
    private val codecs: VideoCodecSupport,
    private val dispatcher: CoroutineDispatcher,
) {
    @Inject
    constructor(extractor: TrailerExtractor, codecs: VideoCodecSupport) : this(extractor, codecs, Dispatchers.IO)

    /** [maxHeight]: 2160p for full-screen trailers; inline 16:9 card previews ask for less. */
    suspend operator fun invoke(youtubeKey: String, maxHeight: Int = TrailerStreamSelector.MAX_HEIGHT): TrailerStream =
        withContext(dispatcher) {
            val extracted = extractor.extract(youtubeKey)
            TrailerStreamSelector.select(extracted, codecs, maxHeight) ?: throw NoTrailerStreamException(youtubeKey)
        }
}
