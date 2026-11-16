package com.seastreams.tv.core.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * OkHttp **network** interceptor that rewrites caching headers on successful responses covered
 * by [HttpCachePolicy], so OkHttp's disk cache keeps them for exactly the policy TTL.
 *
 * It must be registered with `addNetworkInterceptor`: OkHttp's cache stores the response as seen
 * after network interceptors, which is what lets this override the origin's `Cache-Control`.
 */
class CacheTtlInterceptor(private val policy: HttpCachePolicy) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        val ttl = policy.ttlFor(request) ?: return response
        if (!response.isSuccessful) return response

        val builder = response.newBuilder()
            .header("Cache-Control", "public, max-age=${ttl.inWholeSeconds}")
            .removeHeader("Pragma")
            .removeHeader("Expires")
        // `Vary: *` makes a response uncacheable; `Vary: Cookie` is irrelevant for cookie-less calls.
        if (response.headers("Vary").any { it.contains('*') || it.contains("Cookie", ignoreCase = true) }) {
            builder.removeHeader("Vary")
        }
        return builder.build()
    }
}
