package com.seastreams.tv.data.trailer

import android.media.MediaCodecList
import com.seastreams.tv.core.network.OkHttpDownloader
import com.seastreams.tv.domain.usecase.ExtractedTrailer
import com.seastreams.tv.domain.usecase.TrailerAudioCandidate
import com.seastreams.tv.domain.usecase.TrailerContainer
import com.seastreams.tv.domain.usecase.TrailerExtractor
import com.seastreams.tv.domain.usecase.TrailerVideoCandidate
import com.seastreams.tv.domain.usecase.VideoCodecSupport
import com.seastreams.tv.domain.usecase.videoCodecFamily
import dagger.Binds
import dagger.Lazy
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.services.youtube.ItagItem
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.CreationException
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeProgressiveDashManifestCreator
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Initializes NewPipeExtractor once with [OkHttpDownloader] (from `SeaStreamsApp.onCreate`, on IO). */
@Singleton
class NewPipeInitializer @Inject constructor(private val okHttpClient: Lazy<OkHttpClient>) {
    @Volatile
    private var initialized = false

    @Synchronized
    fun ensureInitialized() {
        if (initialized) return
        NewPipe.init(OkHttpDownloader(okHttpClient.get()))
        initialized = true
    }
}

/**
 * [TrailerExtractor] backed by NewPipeExtractor. Adaptive YouTube streams delivered as progressive
 * HTTP get a DASH manifest built locally, because YouTube serves them in short ranges; streams that
 * would need another network round trip (OTF, HLS) are skipped.
 */
class NewPipeTrailerExtractor @Inject constructor(private val initializer: NewPipeInitializer) : TrailerExtractor {

    override suspend fun extract(youtubeKey: String): ExtractedTrailer = withContext(Dispatchers.IO) {
        initializer.ensureInitialized()
        val info = try {
            StreamInfo.getInfo(ServiceList.YouTube, WATCH_URL + youtubeKey)
        } catch (e: ExtractionException) {
            throw IOException("YouTube extraction failed for $youtubeKey", e)
        }
        val duration = info.duration
        val videos = (info.videoOnlyStreams + info.videoStreams).mapNotNull { stream ->
            if (!stream.isUrl || stream.deliveryMethod != DeliveryMethod.PROGRESSIVE_HTTP) return@mapNotNull null
            TrailerVideoCandidate(
                url = stream.content,
                height = stream.height,
                fps = stream.fps,
                bitrate = stream.bitrate,
                codec = stream.codec,
                container = stream.format.toContainer(),
                videoOnly = stream.isVideoOnly(),
                dashManifest = if (stream.isVideoOnly()) manifest(stream.content, stream.itagItem, duration) else null,
            )
        }
        val audios = info.audioStreams.mapNotNull { stream ->
            if (!stream.isUrl || stream.deliveryMethod != DeliveryMethod.PROGRESSIVE_HTTP) return@mapNotNull null
            TrailerAudioCandidate(
                url = stream.content,
                bitrate = stream.averageBitrate.takeIf { it > 0 } ?: stream.bitrate,
                container = stream.format.toContainer(),
                dashManifest = manifest(stream.content, stream.itagItem, duration),
            )
        }
        ExtractedTrailer(videos, audios)
    }

    private fun manifest(url: String, itag: ItagItem?, durationSeconds: Long): String? {
        if (itag == null) return null
        return try {
            YoutubeProgressiveDashManifestCreator.fromProgressiveStreamingUrl(url, itag, durationSeconds)
        } catch (e: CreationException) {
            null
        }
    }

    private fun MediaFormat?.toContainer(): TrailerContainer = when (this) {
        MediaFormat.MPEG_4 -> TrailerContainer.MP4
        MediaFormat.WEBM -> TrailerContainer.WEBM
        MediaFormat.M4A -> TrailerContainer.M4A
        MediaFormat.WEBMA, MediaFormat.WEBMA_OPUS -> TrailerContainer.WEBMA
        else -> TrailerContainer.OTHER
    }

    private companion object {
        const val WATCH_URL = "https://www.youtube.com/watch?v="
    }
}

/**
 * Decoders listed by [MediaCodecList]: a stream qualifies only when a decoder for its codec reports its
 * size and frame rate as supported, so e.g. 4K60 VP9 isn't picked on a 4K30-only decoder.
 */
@Singleton
class MediaCodecVideoSupport @Inject constructor() : VideoCodecSupport {
    private val cache = HashMap<String, Boolean>()
    private val decoders by lazy { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filterNot { it.isEncoder } }

    @Synchronized
    override fun isSupported(codec: String?, height: Int, fps: Int): Boolean {
        val family = videoCodecFamily(codec) ?: "avc"
        val mime = FAMILY_MIME[family] ?: return false
        val width = (height * WIDE_W / WIDE_H).coerceAtLeast(1)
        val rate = fps.takeIf { it > 0 }?.toDouble() ?: DEFAULT_FPS
        return cache.getOrPut("$mime/$height/$fps") {
            decoders.any { info ->
                val type = info.supportedTypes.firstOrNull { it.equals(mime, ignoreCase = true) } ?: return@any false
                val video = runCatching { info.getCapabilitiesForType(type).videoCapabilities }.getOrNull() ?: return@any false
                height <= 0 || video.areSizeAndRateSupported(width, height, rate)
            }
        }
    }

    private companion object {
        const val WIDE_W = 16
        const val WIDE_H = 9
        const val DEFAULT_FPS = 30.0
        val FAMILY_MIME = mapOf(
            "avc" to "video/avc",
            "vp9" to "video/x-vnd.on2.vp9",
            "av1" to "video/av01",
            "hevc" to "video/hevc",
            "vp8" to "video/x-vnd.on2.vp8",
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class TrailerModule {
    @Binds
    abstract fun bindTrailerExtractor(impl: NewPipeTrailerExtractor): TrailerExtractor

    @Binds
    abstract fun bindVideoCodecSupport(impl: MediaCodecVideoSupport): VideoCodecSupport
}
