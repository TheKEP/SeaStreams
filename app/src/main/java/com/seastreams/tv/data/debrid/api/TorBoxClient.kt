package com.seastreams.tv.data.debrid.api

import com.seastreams.tv.data.debrid.DebridServiceKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URLEncoder

/**
 * TorBox (ARCHITECTURE §12, after Nabrio `TorBoxApi.cloud`): `GET user/me` for the account; the library is the
 * user's torrents, Usenet jobs and web downloads (`{section}/mylist?bypass_cache=true`, 1,000 per page), finished and
 * still present only, with their files included; a playback link is `{section}/requestdl?redirect=false`, which takes
 * the key as `token` in the query (TorBox's rule for that endpoint; the URL is never logged or stored).
 */
class TorBoxClient(private val http: DebridHttp, private val endpoints: DebridEndpoints) : DebridLibraryClient {
    override val kind = DebridServiceKind.TORBOX

    override suspend fun account(credential: String): DebridAccount {
        val json = http.get("${endpoints.torBox}/user/me", credential).throwOnHttpError().requireJson()
        val data = torBoxData(json) as? JsonObject ?: throw DebridServiceException()
        val premium = (data.long("plan") ?: 0L) > 0
        return DebridAccount(premium, if (premium) parseTimeMillis(data.str("premium_expires_at")) else null)
    }

    override suspend fun library(credential: String): List<DebridItem> {
        val items = ArrayList<DebridItem>()
        for (section in SECTIONS) {
            if (items.size >= MAX_LIBRARY_ITEMS) break
            val seen = HashSet<String>()
            items += readPages(PAGE, MAX_LIBRARY_ITEMS - items.size) { page ->
                val url = "${endpoints.torBox}/$section/mylist?bypass_cache=true&offset=${page * PAGE}&limit=$PAGE"
                val list = when (val data = torBoxData(http.get(url, credential).throwOnHttpError().requireJson())) {
                    is JsonArray -> objects(data)
                    else -> emptyList()
                }
                // A page that adds nothing new ends the listing (TorBox ignoring the offset would loop forever).
                list.filter { it.str("id")?.let(seen::add) == true }.takeIf { it.isNotEmpty() || list.isEmpty() }
            }.mapNotNull { item(section, it) }
        }
        return items
    }

    /** A finished, present item with its files; anything still downloading (or removed from storage) is skipped. */
    private fun item(section: String, value: JsonObject): DebridItem? {
        val id = value.str("id")?.takeIf { it.isSafeId() } ?: return null
        if (value.bool("download_finished") != true || value.bool("download_present") == false) return null
        val files = objects(value.array("files")).mapNotNull { file ->
            val fileId = file.str("id")?.takeIf { it.isSafeId() } ?: return@mapNotNull null
            val path = file.str("name") ?: file.str("short_name") ?: return@mapNotNull null
            DebridFile(fileId, path, file.long("size") ?: 0L)
        }
        return DebridItem("$section:$id", value.str("name") ?: return null, value.long("size"), files)
    }

    override suspend fun files(credential: String, item: DebridItem): List<DebridFile> = item.files.orEmpty()

    override suspend fun resolve(credential: String, file: DebridFileHandle): String {
        val section = file.itemId.substringBefore(':').takeIf { it in SECTIONS } ?: throw DebridNotFoundException()
        val id = file.itemId.substringAfter(':').takeIf { it.isSafeId() } ?: throw DebridNotFoundException()
        val fileId = file.fileId.takeIf { it.isSafeId() } ?: throw DebridNotFoundException()
        val idKey = when (section) {
            "usenet" -> "usenet_id"
            "webdl" -> "web_id"
            else -> "torrent_id"
        }
        val token = URLEncoder.encode(credential, Charsets.UTF_8.name())
        val url = "${endpoints.torBox}/$section/requestdl?token=$token&$idKey=$id&file_id=$fileId&redirect=false"
        val data = torBoxData(http.get(url, credential).throwOnMissing().requireJson())
        return requireHttps((data as? JsonPrimitive)?.content)
    }

    private companion object {
        val SECTIONS = listOf("torrents", "usenet", "webdl")
        const val PAGE = 1_000
    }
}
