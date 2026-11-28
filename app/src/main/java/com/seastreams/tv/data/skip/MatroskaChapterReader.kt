package com.seastreams.tv.data.skip

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.seastreams.tv.core.util.IoDispatcher
import com.seastreams.tv.domain.model.StreamSource
import com.seastreams.tv.domain.playback.skip.MediaChapter
import com.seastreams.tv.player.usenet.PlaybackDataSources
import dagger.Lazy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.IOException
import javax.inject.Inject

/**
 * Reads a Matroska/WebM stream's chapters with at most two bounded range reads: the first [HEAD_BYTES], then (when
 * the SeekHead points further in) [CHAPTERS_BYTES] at the `Chapters` element. Uses the stream's own headers and
 * Debrid link resolution; Usenet streams are never probed (their connections belong to playback). Any I/O or parse
 * failure is an empty list.
 */
class MatroskaChapterReader @Inject constructor(
    private val client: Lazy<OkHttpClient>,
    private val dataSources: PlaybackDataSources,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    suspend fun read(stream: StreamSource): List<MediaChapter> = withContext(io) {
        val http = OkHttpDataSource.Factory(client.get()).setDefaultRequestProperties(stream.headers)
        val factory = dataSources.probeFactory(stream, http) ?: return@withContext emptyList()
        val uri = Uri.parse(stream.url)
        try {
            val head = readRange(factory, uri, 0, HEAD_BYTES) ?: return@withContext emptyList()
            ensureActive()
            when (val result = EbmlChapterParser.parseHead(head)) {
                is EbmlChapterParser.HeadResult.Found -> result.chapters
                is EbmlChapterParser.HeadResult.At -> readRange(factory, uri, result.offset, CHAPTERS_BYTES)
                    ?.let(EbmlChapterParser::parseChaptersAt).orEmpty()
                EbmlChapterParser.HeadResult.None -> emptyList()
            }
        } catch (e: IOException) {
            emptyList()
        }
    }

    private fun readRange(factory: DataSource.Factory, uri: Uri, offset: Long, length: Int): ByteArray? {
        val source = factory.createDataSource()
        try {
            source.open(DataSpec.Builder().setUri(uri).setPosition(offset).setLength(length.toLong()).build())
            val buffer = ByteArray(length)
            var filled = 0
            while (filled < length) {
                val read = source.read(buffer, filled, length - filled)
                if (read == C.RESULT_END_OF_INPUT) break
                filled += read
            }
            return if (filled == 0) null else buffer.copyOf(filled)
        } finally {
            source.close()
        }
    }

    private companion object {
        const val HEAD_BYTES = 256 * 1024
        const val CHAPTERS_BYTES = 64 * 1024
    }
}
