package com.seastreams.tv.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import javax.inject.Inject
import javax.inject.Singleton

/** A manifest as fetched from an addon: the parsed form plus the raw JSON persisted in Room. */
data class FetchedManifest(val manifestUrl: String, val rawJson: String, val manifest: StremioManifest)

/**
 * Talks to Stremio addons over HTTP. Addon responses are not covered by [HttpCachePolicy], so the
 * origin's own caching headers apply.
 */
@Singleton
class StremioAddonClient @Inject constructor(private val client: HttpClient) {

    /**
     * Downloads and validates the manifest at [manifestUrl] (already normalised by [StremioAddonUrl]).
     * @throws InvalidManifestException when the response is not a valid addon manifest.
     */
    suspend fun fetchManifest(manifestUrl: String): FetchedManifest {
        // Read as text: many addons serve manifest.json as text/plain or without a Content-Type.
        val raw = client.get(manifestUrl).bodyAsText()
        return FetchedManifest(manifestUrl, raw, StremioManifestParser.parse(raw))
    }
}
