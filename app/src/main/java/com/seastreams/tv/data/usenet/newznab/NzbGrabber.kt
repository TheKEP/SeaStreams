package com.seastreams.tv.data.usenet.newznab

import com.seastreams.tv.core.util.IoDispatcher
import com.seastreams.tv.data.usenet.headers.HeaderUse
import com.seastreams.tv.usenet.cache.NzbFileCache
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** The link served something other than an NZB (an HTML error page, an expired grab, an empty file). */
class NotAnNzbException(message: String) : IOException(message)

/** Where one NZB comes from: [cacheKey] identifies it in the NZB cache (hashed there; may hold the URL). */
data class NzbRequest(val url: String, val cacheKey: String, val headers: Map<String, String> = emptyMap())

/**
 * Downloads NZBs (PRD §12): the `Download` header preset (plus a stream's own headers), at most *Max NZB size*,
 * and the text must contain `<segment` or it is refused. Grabs are cached for 14 days ([NzbFileCache]) so
 * reopening a release spends no indexer grab. At most *Max concurrent imports* grabs run at once (`0` = unlimited).
 */
@Singleton
class NzbGrabber @Inject constructor(
    private val http: UsenetHttp,
    private val cache: NzbFileCache,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private var limit = -1
    private var permits: Semaphore? = null

    suspend fun grab(request: NzbRequest, maxNzbBytes: Long, maxConcurrentImports: Int): String {
        withContext(io) { cache.get(request.cacheKey) }?.let { return it }
        val gate = gate(maxConcurrentImports)
        val fetch: suspend () -> String = { download(request, maxNzbBytes) }
        val text = if (gate == null) fetch() else gate.withPermit { fetch() }
        withContext(io) { cache.put(request.cacheKey, text) }
        return text
    }

    private suspend fun download(request: NzbRequest, maxNzbBytes: Long): String {
        val headers = http.headers(HeaderUse.DOWNLOAD) + request.headers
        val answer = withContext(io) { http.get(request.url, headers, maxNzbBytes) }
        NewznabXmlScanner.error(answer.body)?.let { throw it }
        if (!answer.ok) throw NotAnNzbException("The NZB download failed (HTTP ${answer.code})")
        if (!answer.body.contains("<segment")) throw NotAnNzbException("The link didn't return an NZB")
        return answer.body
    }

    @Synchronized
    private fun gate(maxConcurrentImports: Int): Semaphore? {
        if (maxConcurrentImports <= 0) return null
        if (maxConcurrentImports != limit) {
            limit = maxConcurrentImports
            permits = Semaphore(maxConcurrentImports)
        }
        return permits
    }
}
