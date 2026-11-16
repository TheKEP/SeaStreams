package com.seastreams.tv.core.network

/** Supplies the user's TMDB credential (v3 API key or v4 read-access token). */
fun interface TmdbApiKeyProvider {
    suspend fun currentApiKey(): String?
}
