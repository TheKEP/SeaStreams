package com.seastreams.tv.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLPathPart
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

    /** `/stream/{type}/{id}.json` from the addon installed at [manifestUrl]. */
    suspend fun fetchStreams(manifestUrl: String, type: String, id: String): List<StremioStream> =
        StremioResourceParser.parseStreams(client.get(resourceUrl(manifestUrl, "stream", type, id)).bodyAsText())

    /** `/subtitles/{type}/{id}.json` from the addon installed at [manifestUrl]. */
    suspend fun fetchSubtitles(manifestUrl: String, type: String, id: String): List<StremioSubtitle> =
        StremioResourceParser.parseSubtitles(client.get(resourceUrl(manifestUrl, "subtitles", type, id)).bodyAsText())

    /**
     * One page of `/catalog/{type}/{id}.json` from the addon at [manifestUrl]: page 1 when [skip] is 0,
     * else `/catalog/{type}/{id}/skip={skip}.json` (the Stremio paging extra).
     */
    suspend fun fetchCatalog(manifestUrl: String, type: String, catalogId: String, skip: Int = 0): List<StremioMetaPreview> =
        StremioResourceParser.parseMetas(client.get(catalogUrl(manifestUrl, type, catalogId, skip)).bodyAsText())

    companion object {
        /** `{base}/{resource}/{type}/{id}[/{extra}].json{?query}`; ids and extras are path-encoded. */
        fun resourceUrl(manifestUrl: String, resource: String, type: String, id: String, extra: String? = null): String {
            // Addon configuration lives in the path before manifest.json; the query (if any) is kept.
            val query = manifestUrl.substringAfter("?", "").let { if (it.isEmpty()) "" else "?$it" }
            val extraSegment = extra?.let { "/${it.encodeURLPathPart()}" }.orEmpty()
            return "${StremioAddonUrl.transportBase(manifestUrl)}/$resource/$type/${id.encodeURLPathPart()}$extraSegment.json$query"
        }

        fun catalogUrl(manifestUrl: String, type: String, catalogId: String, skip: Int): String =
            resourceUrl(manifestUrl, "catalog", type, catalogId, extra = if (skip > 0) "skip=$skip" else null)
    }
}
