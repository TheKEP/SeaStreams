package com.seastreams.tv.data.debrid.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.net.URI
import java.net.URISyntaxException

/**
 * One finished entry of a library listing (ARCHITECTURE §12): a torrent, download, saved link, cloud folder or file
 * the user already has. [id] is the client's own reference (it may carry a section prefix such as `usenet:12`) and is
 * only interpreted by the client that produced it.
 */
data class DebridItem(
    val id: String,
    val name: String,
    val sizeBytes: Long? = null,
    /** The item's files when the listing already carries them; `null` = ask [DebridLibraryClient.files]. */
    val files: List<DebridFile>? = null,
)

/** One file of a [DebridItem]. [path] is relative to the item (folders separated by `/`), the file name last. */
class DebridFile(
    val id: String,
    val path: String,
    val sizeBytes: Long,
    /** The service link the file is resolved from (a hoster / restricted link), never handed to a player as is. */
    val link: String? = null,
) {
    val name: String get() = path.substringAfterLast('/')

    override fun equals(other: Any?): Boolean =
        other is DebridFile && other.id == id && other.path == path && other.sizeBytes == sizeBytes && other.link == link

    override fun hashCode(): Int = 31 * (31 * id.hashCode() + path.hashCode()) + sizeBytes.hashCode()

    /** Links can carry account tokens: never printed. */
    override fun toString(): String = "DebridFile($id, $path, $sizeBytes)"
}

/** What [DebridLibraryClient.resolve] needs about one file. */
class DebridFileHandle(val itemId: String, val fileId: String, val link: String?) {
    override fun toString(): String = "DebridFileHandle($itemId, $fileId)"
}

/** The file (or its item) is no longer in the account: deleted, expired or never there. */
class DebridNotFoundException(message: String = "That file is no longer in your Debrid account") : DebridException(message)

/** Whether [name] ends in a video extension (PRD §14: only video files count). */
fun isVideoFileName(name: String): Boolean = VIDEO_EXTENSION.containsMatchIn(name)

private val VIDEO_EXTENSION = Regex("(?i)[.](mkv|mp4|m4v|avi|mov|ts|m2ts|mts|webm|mpg|mpeg|wmv|flv)$")

/** The listing is capped (PRD §14): larger libraries are read up to this many items per provider. */
const val MAX_LIBRARY_ITEMS = 2_000

/** [link] when it is an absolute `https://` URL with a host and no user info; anything else is refused. */
internal fun requireHttps(link: String?): String {
    val value = link?.trim().orEmpty()
    val uri = try {
        URI(value)
    } catch (e: URISyntaxException) {
        null
    }
    if (uri == null || !uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank() || uri.userInfo != null) {
        throw DebridServiceException("The service didn't return a secure playback link")
    }
    return value
}

/** `404` → [DebridNotFoundException], then the usual [DebridAnswer.throwOnHttpError] mapping. */
internal fun DebridAnswer.throwOnMissing(): DebridAnswer {
    if (code == HTTP_NOT_FOUND) throw DebridNotFoundException()
    return throwOnHttpError()
}

/** The body as a JSON array, or `null` when it is anything else. */
internal fun DebridAnswer.jsonArray(): JsonArray? = jsonElement() as? JsonArray

internal fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

/** The objects of [array], skipping anything else. */
internal fun objects(array: JsonArray?): List<JsonObject> = array?.filterIsInstance<JsonObject>().orEmpty()

/**
 * Reads a paginated listing: [fetch] gets 0-based page `n` and returns its entries (`null` or fewer than [pageSize]
 * ends the listing). Stops at [MAX_LIBRARY_ITEMS] entries.
 */
internal suspend fun <T> readPages(pageSize: Int, max: Int = MAX_LIBRARY_ITEMS, fetch: suspend (page: Int) -> List<T>?): List<T> {
    val all = ArrayList<T>()
    var page = 0
    while (all.size < max) {
        val entries = fetch(page) ?: break
        all += entries.take(max - all.size)
        if (entries.size < pageSize) break
        page++
    }
    return all
}

private const val HTTP_NOT_FOUND = 404
