package com.seastreams.tv.core.network

import okhttp3.OkHttpClient
import okhttp3.Request.Builder
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException

/**
 * NewPipeExtractor's HTTP layer, backed by the app's shared [OkHttpClient] (its own copy without the
 * disk cache, so YouTube pages never take space from TMDB/addon responses).
 */
class OkHttpDownloader(client: OkHttpClient) : Downloader() {

    private val client: OkHttpClient = client.withoutDiskCache()

    override fun execute(request: Request): Response {
        val body = request.dataToSend()?.toRequestBody()
        val builder = Builder()
            .url(request.url())
            .method(request.httpMethod(), body)
            .header("User-Agent", USER_AGENT)
        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { builder.addHeader(name, it) }
        }
        client.newCall(builder.build()).execute().use { response ->
            if (response.code == HTTP_TOO_MANY_REQUESTS) throw ReCaptchaException("reCaptcha challenge requested", request.url())
            val headers = response.headers.toMultimap()
            return Response(response.code, response.message, headers, response.body.string(), response.request.url.toString())
        }
    }

    private companion object {
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
    }
}
