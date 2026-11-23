package com.seastreams.tv.data.usenet.newznab

import com.seastreams.tv.core.util.IoDispatcher
import com.seastreams.tv.data.usenet.headers.HeaderUse
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** The indexer `Test` result, worded for a toast (TV) or the portal's status line. */
sealed interface IndexerTestResult {
    val message: String

    data class Success(val name: String, val caps: NewznabCaps) : IndexerTestResult {
        override val message: String
            get() {
                val ids = listOfNotNull(
                    "movies by IMDb id".takeIf { caps.movieSearch.supports("imdbid") },
                    "episodes by IMDb id".takeIf { caps.tvSearch.supports("imdbid") },
                )
                return if (ids.isEmpty()) "$name works (text search only)" else "$name works: searches ${ids.joinToString(" and ")}"
            }
    }

    data class Failed(override val message: String) : IndexerTestResult
}

/**
 * A Newznab API client (PRD §12): `t=caps` (cached 14 days by [NewznabCapsCache]) and the searches
 * [NewznabQueries] plans, sent with the `Search` header preset. The API key is appended only when the request is
 * built and never appears in an exception message. Answers are read by [NewznabXmlScanner]; a Newznab `<error>`
 * becomes a [NewznabException] (codes 100–104 = account problems).
 */
@Singleton
class NewznabClient @Inject constructor(
    private val http: UsenetHttp,
    private val capsCache: NewznabCapsCache,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    suspend fun caps(endpoint: NewznabEndpoint, refresh: Boolean = false): NewznabCaps {
        if (!refresh) withContext(io) { capsCache.get(endpoint.apiUrl) }?.let { return it }
        val xml = request(endpoint, NewznabQueries.CAPS)
        if (!xml.contains("<caps") && !xml.contains("<searching")) throw NewznabException(null, "${endpoint.name} didn't answer like a Newznab indexer")
        withContext(io) { capsCache.put(endpoint.apiUrl, xml) }
        return NewznabXmlScanner.caps(xml)
    }

    suspend fun search(endpoint: NewznabEndpoint, query: NewznabQuery): List<NewznabItem> =
        NewznabXmlScanner.items(request(endpoint, query))

    /** The `Test` button on the TV and in the portal: a fresh `t=caps` with the typed or saved key. */
    suspend fun test(endpoint: NewznabEndpoint): IndexerTestResult = try {
        IndexerTestResult.Success(endpoint.name, caps(endpoint, refresh = true))
    } catch (e: NewznabException) {
        IndexerTestResult.Failed(e.message ?: "The indexer refused the request")
    } catch (e: IOException) {
        IndexerTestResult.Failed("Couldn't reach ${endpoint.name}")
    }

    private suspend fun request(endpoint: NewznabEndpoint, query: NewznabQuery): String {
        val url = requestUrl(endpoint, query) ?: throw NewznabException(null, "${endpoint.name}'s address isn't valid")
        val headers = http.headers(HeaderUse.SEARCH)
        val answer = withContext(io) { http.get(url.toString(), headers, MAX_RESPONSE_BYTES) }
        NewznabXmlScanner.error(answer.body)?.let { throw it }
        if (!answer.ok) throw httpError(answer.code)
        return answer.body
    }

    private fun httpError(code: Int): NewznabException = when (code) {
        401, 403 -> NewznabException.fromCode(100, null)
        429 -> NewznabException.fromCode(429, null)
        else -> NewznabException(null, "The indexer answered HTTP $code")
    }

    companion object {
        const val MAX_RESPONSE_BYTES = 8L * 1024 * 1024

        /** [endpoint]'s API URL with the query's parameters, `o=xml`, and the key last. */
        fun requestUrl(endpoint: NewznabEndpoint, query: NewznabQuery): HttpUrl? {
            val base = endpoint.apiUrl.toHttpUrlOrNull() ?: return null
            return base.newBuilder().apply {
                query.params.forEach { (name, value) -> addQueryParameter(name, value) }
                addQueryParameter("o", "xml")
                if (endpoint.apiKey.isNotEmpty()) addQueryParameter("apikey", endpoint.apiKey)
            }.build()
        }
    }
}
