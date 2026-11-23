package com.seastreams.tv.data.debrid.api

import com.seastreams.tv.data.debrid.DebridServiceKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder

/**
 * AllDebrid (ARCHITECTURE §12): `GET v4/user` for the account; the library is the user's ready magnets
 * (`v4.1/magnet/status`, status `Ready` only) plus saved links (`v4/user/links`); a ready magnet's files come from
 * `v4/magnet/files` (nested folders flattened into paths); a playback link is `v4/link/unlock`. Every call carries
 * `agent=SeaStreams`; errors arrive as HTTP 200 with `error.code`.
 */
class AllDebridClient(private val http: DebridHttp, private val endpoints: DebridEndpoints) : DebridLibraryClient {
    override val kind = DebridServiceKind.ALL_DEBRID

    override suspend fun account(credential: String): DebridAccount {
        val json = http.get(url("v4/user"), credential).throwOnHttpError().requireJson()
        val user = allDebridData(json).obj("user") ?: throw DebridServiceException()
        val premium = user.bool("isPremium") == true
        return DebridAccount(premium, if (premium) user.long("premiumUntil")?.let(::epochToMillis) else null)
    }

    override suspend fun library(credential: String): List<DebridItem> {
        val status = allDebridData(http.get(url("v4.1/magnet/status") + "&status=ready", credential).throwOnHttpError().requireJson())
        val magnets = when (val list = status["magnets"]) {
            is JsonArray -> objects(list)
            is JsonObject -> list.values.filterIsInstance<JsonObject>()
            else -> emptyList()
        }.mapNotNull { magnet ->
            val id = magnet.str("id")?.takeIf { it.isSafeId() } ?: return@mapNotNull null
            if (magnet.long("statusCode") != READY && magnet.str("status") != "Ready") return@mapNotNull null
            DebridItem(MAGNET + id, magnet.str("filename") ?: return@mapNotNull null, magnet.long("size"))
        }.take(MAX_LIBRARY_ITEMS)
        val room = MAX_LIBRARY_ITEMS - magnets.size
        if (room <= 0) return magnets
        val saved = allDebridData(http.get(url("v4/user/links"), credential).throwOnHttpError().requireJson())
        val links = objects(saved.array("links")).mapNotNull { entry ->
            val link = entry.str("link") ?: return@mapNotNull null
            val name = entry.str("filename") ?: return@mapNotNull null
            val size = entry.long("size") ?: 0L
            DebridItem(LINK + link, name, size, files = listOf(DebridFile(SINGLE_FILE, name, size, link)))
        }.take(room)
        return magnets + links
    }

    override suspend fun files(credential: String, item: DebridItem): List<DebridFile> {
        item.files?.let { return it }
        val id = item.id.removePrefix(MAGNET).takeIf { item.id.startsWith(MAGNET) && it.isSafeId() } ?: return emptyList()
        return magnetFiles(credential, id)
    }

    private suspend fun magnetFiles(credential: String, id: String): List<DebridFile> {
        val data = allDebridData(http.get(url("v4/magnet/files") + "&id[]=$id", credential).throwOnMissing().requireJson())
        val magnet = objects(data.array("magnets")).firstOrNull { it.str("id") == id } ?: throw DebridNotFoundException()
        magnet.obj("error")?.let { throw allDebridError(it.str("code")) }
        return buildList { flatten(magnet.array("files"), prefix = "", into = this) }
    }

    /** AllDebrid's file tree: `{n, s, l}` files and `{n, e: [...]}` folders. */
    private fun flatten(entries: JsonArray?, prefix: String, into: MutableList<DebridFile>) {
        for (entry in objects(entries)) {
            val name = entry.str("n") ?: continue
            val path = if (prefix.isEmpty()) name else "$prefix/$name"
            val children = entry.array("e")
            if (children != null) {
                if (path.count { it == '/' } < MAX_DEPTH) flatten(children, path, into)
            } else {
                val link = entry.str("l") ?: continue
                into += DebridFile(path, path, entry.long("s") ?: 0L, link)
            }
        }
    }

    override suspend fun resolve(credential: String, file: DebridFileHandle): String {
        val link = if (file.itemId.startsWith(MAGNET)) {
            // Read again so a deleted magnet is noticed here, not by the player.
            val id = file.itemId.removePrefix(MAGNET).takeIf { it.isSafeId() } ?: throw DebridNotFoundException()
            magnetFiles(credential, id).firstOrNull { it.id == file.fileId }?.link ?: throw DebridNotFoundException()
        } else {
            file.link ?: throw DebridNotFoundException()
        }
        val encoded = URLEncoder.encode(link, Charsets.UTF_8.name())
        val data = allDebridData(http.get(url("v4/link/unlock") + "&link=$encoded", credential).throwOnMissing().requireJson())
        // A `delayed` answer means the hoster file is still being generated: not playable now.
        return requireHttps(data.str("link"))
    }

    private fun url(path: String) = "${endpoints.allDebrid}/$path?agent=$ALL_DEBRID_AGENT"

    private companion object {
        const val MAGNET = "m:"
        const val LINK = "l:"
        const val SINGLE_FILE = "0"
        const val READY = 4L
        const val MAX_DEPTH = 8
    }
}
