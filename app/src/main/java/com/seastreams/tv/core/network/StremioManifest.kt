package com.seastreams.tv.core.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** A resource an addon serves (`stream`, `subtitles`, `meta`, `catalog`, …), optionally narrowed. */
data class StremioResource(
    val name: String,
    /** Types this resource applies to; empty means "inherit the manifest's [StremioManifest.types]". */
    val types: List<String>,
    /** Id prefixes this resource applies to; empty means "inherit the manifest's id prefixes". */
    val idPrefixes: List<String>,
)

/**
 * One `catalogs[]` entry. [requiresExtra] is true when the catalog can only be fetched with an extra
 * parameter (e.g. search- or genre-only catalogs), so it cannot be shown as a plain row.
 */
data class StremioCatalog(val type: String, val id: String, val name: String, val requiresExtra: Boolean = false)

/** The subset of a Stremio addon `manifest.json` that SeaStreams uses. */
data class StremioManifest(
    val id: String,
    val version: String,
    val name: String,
    val description: String,
    val logo: String?,
    val types: List<String>,
    val idPrefixes: List<String>,
    val resources: List<StremioResource>,
    val catalogs: List<StremioCatalog>,
) {
    /** True when the addon declares [resource] for [type] (e.g. `stream` for `movie`). */
    fun supports(resource: String, type: String): Boolean = resources.any { res ->
        res.name == resource && type in res.types.ifEmpty { types }
    }
}

class InvalidManifestException(message: String) : IllegalArgumentException(message)

/**
 * Parses Stremio manifests. `resources` entries may be plain strings (`"stream"`) or objects
 * (`{"name":"stream","types":["movie"],"idPrefixes":["tt"]}`), so parsing walks the JSON tree
 * instead of relying on a fixed serializer. Unknown fields are ignored.
 */
object StremioManifestParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** @throws InvalidManifestException when the payload is not a usable addon manifest. */
    fun parse(raw: String): StremioManifest {
        val root = try {
            json.parseToJsonElement(raw).jsonObject
        } catch (e: IllegalArgumentException) {
            throw InvalidManifestException("manifest.json is not a JSON object")
        }
        val id = root.string("id") ?: throw InvalidManifestException("Manifest is missing \"id\"")
        val name = root.string("name") ?: throw InvalidManifestException("Manifest is missing \"name\"")
        val version = root.string("version") ?: throw InvalidManifestException("Manifest is missing \"version\"")
        val resources = (root["resources"] as? JsonArray).orEmpty().mapNotNull(::parseResource)
        if (resources.isEmpty()) throw InvalidManifestException("Manifest declares no resources")
        return StremioManifest(
            id = id,
            version = version,
            name = name,
            description = root.string("description").orEmpty(),
            logo = root.string("logo"),
            types = root.stringList("types"),
            idPrefixes = root.stringList("idPrefixes"),
            resources = resources,
            catalogs = (root["catalogs"] as? JsonArray).orEmpty().mapNotNull(::parseCatalog),
        )
    }

    private fun parseResource(element: JsonElement): StremioResource? = when (element) {
        is JsonPrimitive -> element.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && element.isString }
            ?.let { StremioResource(it, emptyList(), emptyList()) }
        is JsonObject -> element.string("name")?.let { name ->
            StremioResource(name, element.stringList("types"), element.stringList("idPrefixes"))
        }
        else -> null
    }

    private fun parseCatalog(element: JsonElement): StremioCatalog? {
        val obj = element as? JsonObject ?: return null
        val type = obj.string("type") ?: return null
        val id = obj.string("id") ?: return null
        val requiredExtra = (obj["extra"] as? JsonArray).orEmpty().any { extra ->
            ((extra as? JsonObject)?.get("isRequired") as? JsonPrimitive)?.content == "true"
        }
        return StremioCatalog(
            type = type,
            id = id,
            name = obj.string("name") ?: id,
            requiresExtra = requiredExtra || obj.stringList("extraRequired").isNotEmpty(),
        )
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.stringList(key: String): List<String> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
}

/** Normalises user-entered addon links into canonical `https://…/manifest.json` URLs. */
object StremioAddonUrl {

    private const val MANIFEST = "manifest.json"

    /**
     * Accepts `stremio://`, `http(s)://` or scheme-less links. Returns `null` for anything that is
     * not a plausible addon URL. A missing `/manifest.json` suffix is appended.
     */
    fun normalize(input: String): String? {
        var url = input.trim()
        if (url.isEmpty() || url.any { it.isWhitespace() }) return null
        url = when {
            url.startsWith("stremio://", ignoreCase = true) -> "https://" + url.substring("stremio://".length)
            url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true) -> url
            url.contains("://") -> return null
            else -> "https://$url"
        }
        val parsed = url.toHttpUrlOrNull() ?: return null
        val path = parsed.encodedPath.trimEnd('/')
        val withManifest = if (path.endsWith("/$MANIFEST")) path else "$path/$MANIFEST"
        return parsed.newBuilder().encodedPath(withManifest).fragment(null).build().toString()
    }

    /** Base URL that resource paths (`/stream/…`, `/subtitles/…`) are resolved against. */
    fun transportBase(manifestUrl: String): String = manifestUrl.substringBefore("?").removeSuffix(MANIFEST).trimEnd('/')
}
