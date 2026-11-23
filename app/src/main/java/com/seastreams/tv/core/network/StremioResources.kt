package com.seastreams.tv.core.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * One playable entry from an addon's `/stream/{type}/{id}.json`. Direct `url` streams and Usenet `nzbUrl` streams
 * (PRD §12, optionally with their own NNTP `servers`) are kept: torrent (`infoHash`), YouTube (`ytId`) and browser
 * (`externalUrl`) entries can't be handed to a video player as-is.
 */
data class StremioStream(
    /** The video URL, or for a Usenet stream its [nzbUrl]. */
    val url: String,
    /** Short source label, often multi-line (e.g. `"Torrentio\n4k"`). */
    val name: String,
    /** Longer description (file name, size, codecs), often multi-line. */
    val description: String,
    /** Request headers the addon says the stream needs (`behaviorHints.proxyHeaders.request`). */
    val headers: Map<String, String>,
    /** An NZB link to stream through the Usenet engine instead of [url] playing directly. */
    val nzbUrl: String? = null,
    /** `nntp(s)://user:pass@host:port/connections` servers the addon supplies for [nzbUrl] (empty = the user's providers). */
    val servers: List<String> = emptyList(),
    /** `behaviorHints.filename`: the release / file name, read by stream filtering (PRD §13). */
    val filename: String? = null,
    /** `behaviorHints.videoSize` in bytes. */
    val videoSize: Long? = null,
)

/** One subtitle file from an addon's `/subtitles/{type}/{id}.json`. */
data class StremioSubtitle(val id: String, val url: String, val language: String)

/** One entry of an addon's `/catalog/{type}/{id}.json` `metas` array (only what SeaStreams uses). */
data class StremioMetaPreview(val id: String, val type: String)

/** Lenient parsers for Stremio resource responses; malformed entries are skipped, not fatal. */
object StremioResourceParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parseStreams(raw: String): List<StremioStream> = array(raw, "streams").mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val direct = obj.httpUrl()
        val nzb = if (direct == null) obj.string("nzbUrl")?.takeIf(::isHttp) else null
        StremioStream(
            url = direct ?: nzb ?: return@mapNotNull null,
            name = obj.string("name").orEmpty(),
            description = (obj.string("description") ?: obj.string("title")).orEmpty(),
            headers = requestHeaders(obj),
            nzbUrl = nzb,
            servers = if (nzb == null) emptyList() else servers(obj),
            filename = (obj["behaviorHints"] as? JsonObject)?.string("filename"),
            videoSize = videoSize(obj),
        )
    }

    fun parseSubtitles(raw: String): List<StremioSubtitle> = array(raw, "subtitles").mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val url = obj.httpUrl() ?: return@mapNotNull null
        StremioSubtitle(id = obj.string("id") ?: url, url = url, language = obj.string("lang").orEmpty())
    }

    fun parseMetas(raw: String): List<StremioMetaPreview> = array(raw, "metas").mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val id = obj.string("id") ?: return@mapNotNull null
        StremioMetaPreview(id = id, type = obj.string("type").orEmpty())
    }

    private fun array(raw: String, key: String): List<JsonElement> = try {
        (json.parseToJsonElement(raw).jsonObject[key] as? JsonArray).orEmpty()
    } catch (e: IllegalArgumentException) {
        emptyList()
    }

    private fun requestHeaders(stream: JsonObject): Map<String, String> {
        val hints = stream["behaviorHints"] as? JsonObject ?: return emptyMap()
        val proxy = hints["proxyHeaders"] as? JsonObject ?: return emptyMap()
        val request = proxy["request"] as? JsonObject ?: return emptyMap()
        return request.mapNotNull { (name, value) ->
            (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { name to it }
        }.toMap()
    }

    /** `behaviorHints.videoSize` when it is a positive whole number of bytes. */
    private fun videoSize(stream: JsonObject): Long? {
        val hints = stream["behaviorHints"] as? JsonObject ?: return null
        return (hints["videoSize"] as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toDoubleOrNull()?.toLong()?.takeIf { it > 0 }
    }

    /** The entry's `url` when it is a plain http(s) link a video player can open. */
    private fun JsonObject.httpUrl(): String? = string("url")?.takeIf(::isHttp)

    private fun isHttp(url: String) = url.startsWith("http://") || url.startsWith("https://")

    /** `servers`: NNTP server URLs (strings) for an `nzbUrl` stream; anything else is skipped. */
    private fun servers(stream: JsonObject): List<String> = (stream["servers"] as? JsonArray).orEmpty().mapNotNull { entry ->
        (entry as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
}
