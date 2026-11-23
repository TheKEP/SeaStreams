package com.seastreams.tv.data.usenet.newznab

import com.seastreams.tv.core.datastore.UsenetSettingsStore
import com.seastreams.tv.core.network.withoutDiskCache
import com.seastreams.tv.data.usenet.headers.HeaderUse
import dagger.Lazy
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** A body over the allowed size (an NZB over *Max NZB size*, or a runaway search page). */
class ResponseTooLargeException(message: String) : IOException(message)

/**
 * The HTTP side of Usenet search (PRD §12): the shared OkHttp client without its disk cache (indexer answers hold
 * per-user links), the `Search` / `Download` header presets, and size-capped reads. Blocking calls: IO only.
 */
@Singleton
class UsenetHttp @Inject constructor(
    /** Lazy: the shared client owns the HTTP disk cache, which must not be opened on the main thread. */
    private val okHttp: Lazy<OkHttpClient>,
    private val store: UsenetSettingsStore,
) {
    private val client: OkHttpClient by lazy { okHttp.get().withoutDiskCache() }

    /** The headers of the preset chosen for [use], with the latest fetched tool versions. */
    suspend fun headers(use: HeaderUse): Map<String, String> {
        val settings = store.headers.first()
        return settings.presetFor(use).headers(settings.versions)
    }

    /** GETs [url] with [headers]; the body (at most [maxBytes]) and the status, or an [IOException]. */
    fun get(url: String, headers: Map<String, String>, maxBytes: Long): HttpText {
        val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        client.newCall(request).execute().use { response ->
            return HttpText(response.code, readCapped(response, maxBytes), response.header("Content-Type"))
        }
    }

    private fun readCapped(response: Response, maxBytes: Long): String {
        val body = response.body
        val declared = body.contentLength()
        if (declared > maxBytes) throw ResponseTooLargeException("The file is ${declared / MB} MB, over the ${maxBytes / MB} MB limit")
        val source = body.source()
        val buffer = Buffer()
        while (source.read(buffer, CHUNK) != -1L) {
            if (buffer.size > maxBytes) throw ResponseTooLargeException("The file is over the ${maxBytes / MB} MB limit")
        }
        return buffer.readUtf8()
    }

    private companion object {
        const val CHUNK = 64L * 1024
        const val MB = 1024L * 1024
    }
}

/** A response body read as text. */
class HttpText(val code: Int, val body: String, val contentType: String?) {
    val ok: Boolean get() = code in 200..299
}
