package com.seastreams.tv.core.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * One playable entry from an addon's `/stream/{type}/{id}.json`. Only direct `url` streams are kept:
 * torrent (`infoHash`), YouTube (`ytId`) and browser (`externalUrl`) entries can't be handed to a
 * video player as-is.
 */
data class StremioStream(
    val url: String,
    /** Short source label, often multi-line (e.g. `"Torrentio\n4k"`). */
    val name: String,
    /** Longer description (file name, size, codecs), often multi-line. */
    val description: String,
    /** Request headers the addon says the stream needs (`behaviorHints.proxyHeaders.request`). */
    val headers: Map<String, String>,
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
        val url = obj.string("url")?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: return@mapNotNull null
        StremioStream(
            url = url,
            name = obj.string("name").orEmpty(),
            description = (obj.string("description") ?: obj.string("title")).orEmpty(),
            headers = requestHeaders(obj),
        )
    }

    fun parseSubtitles(raw: String): List<StremioSubtitle> = array(raw, "subtitles").mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val url = obj.string("url")?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: return@mapNotNull null
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

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
}
