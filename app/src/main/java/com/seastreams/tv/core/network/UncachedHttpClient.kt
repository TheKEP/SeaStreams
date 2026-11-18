package com.seastreams.tv.core.network

import okhttp3.OkHttpClient

/**
 * A copy of the shared client (same connection pool, dispatcher and interceptors) without the 100 MB
 * disk cache: video bytes and YouTube pages must never evict TMDB/addon responses from it.
 */
fun OkHttpClient.withoutDiskCache(): OkHttpClient = newBuilder().cache(null).build()
