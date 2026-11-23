package com.seastreams.tv.data.debrid.api

import com.seastreams.tv.core.network.withoutDiskCache
import com.seastreams.tv.core.util.IoDispatcher
import dagger.Lazy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.CacheControl
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** A Debrid service refused or garbled a request. Deliberately not an [IOException]: that means *unreachable*. */
open class DebridException(message: String) : Exception(message)

/** The key or token was rejected (`401`/`403` or the service's own auth error): *Invalid API key or token* / *Sign-in needed*. */
class DebridAuthException(message: String = "Invalid API key or token") : DebridException(message)

/** `429` or the service's own rate-limit error. */
class DebridRateLimitedException : DebridException("Rate limited")

/** An answer the client couldn't use (no message from the service is ever echoed, it may hold account data). */
class DebridServiceException(message: String = "Unexpected answer") : DebridException(message)

/** Base URLs of every Debrid API (overridden in tests). */
data class DebridEndpoints(
    val realDebridApi: String = "https://api.real-debrid.com/rest/1.0",
    val realDebridOauth: String = "https://api.real-debrid.com/oauth/v2",
    val allDebrid: String = "https://api.alldebrid.com",
    val torBox: String = "https://api.torbox.app/v1/api",
    val premiumize: String = "https://www.premiumize.me/api",
    val debridLink: String = "https://debrid-link.com/api",
    val offcloud: String = "https://offcloud.com/api",
    val putIo: String = "https://api.put.io/v2",
)

/** A Debrid response read as text (capped at [DebridHttp.MAX_BODY_BYTES]). */
class DebridAnswer(val code: Int, val body: String) {
    val ok: Boolean get() = code in 200..299

    /** The body as a JSON object, or `null` when it isn't one. */
    fun json(): JsonObject? = try {
        lenientJson.parseToJsonElement(body) as? JsonObject
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    /** `401`/`403` → [DebridAuthException], `429` → [DebridRateLimitedException], `5xx` → unreachable ([IOException]). */
    fun throwOnHttpError(): DebridAnswer {
        when {
            code == HTTP_UNAUTHORIZED || code == HTTP_FORBIDDEN -> throw DebridAuthException()
            code == HTTP_TOO_MANY_REQUESTS -> throw DebridRateLimitedException()
            code >= HTTP_SERVER_ERROR -> throw IOException("HTTP $code")
        }
        return this
    }

    override fun toString(): String = "DebridAnswer($code)"
}

private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * The HTTP side of every Debrid call (ARCHITECTURE §12): the shared OkHttp client without its disk cache, `no-store`
 * on every request (account data and, later, library listings and links never reach the HTTP cache), credentials in
 * `Authorization: Bearer` headers. Where a service insists on a query parameter (Premiumize `apikey`), the URL is
 * never logged, persisted or returned. Calls run on [io] and are interruptible.
 */
@Singleton
class DebridHttp internal constructor(
    private val clientProvider: () -> OkHttpClient,
    private val io: CoroutineDispatcher,
) {
    @Inject
    constructor(okHttp: Lazy<OkHttpClient>, @IoDispatcher io: CoroutineDispatcher) :
        this({ okHttp.get().withoutDiskCache() }, io)

    private val client: OkHttpClient by lazy(clientProvider)

    suspend fun get(url: String, bearer: String? = null): DebridAnswer = execute(request(url, bearer).get())

    suspend fun postForm(url: String, fields: Map<String, String>, bearer: String? = null): DebridAnswer {
        val form = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
        return execute(request(url, bearer).post(form))
    }

    suspend fun postJson(url: String, json: JsonElement, bearer: String? = null): DebridAnswer =
        execute(request(url, bearer).post(json.toString().toRequestBody(JSON_TYPE)))

    private fun request(url: String, bearer: String?): Request.Builder = Request.Builder()
        .url(url)
        .cacheControl(NO_STORE)
        .header("Accept", "application/json")
        .apply { bearer?.let { header("Authorization", "Bearer $it") } }

    private suspend fun execute(builder: Request.Builder): DebridAnswer = runInterruptible(io) {
        val request = try {
            builder.build()
        } catch (e: IllegalArgumentException) {
            throw IOException("Invalid address")
        }
        client.newCall(request).execute().use { response ->
            DebridAnswer(response.code, readCapped(response.body.source()))
        }
    }

    private fun readCapped(source: okio.BufferedSource): String {
        val buffer = Buffer()
        while (source.read(buffer, CHUNK) != -1L) {
            if (buffer.size > MAX_BODY_BYTES) throw IOException("Response too large")
        }
        return buffer.readUtf8()
    }

    companion object {
        const val MAX_BODY_BYTES = 4L * 1024 * 1024
        private const val CHUNK = 64L * 1024
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
        private val NO_STORE: CacheControl = CacheControl.Builder().noStore().noCache().build()
    }
}

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_SERVER_ERROR = 500
