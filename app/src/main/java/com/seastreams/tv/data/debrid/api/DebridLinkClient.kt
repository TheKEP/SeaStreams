package com.seastreams.tv.data.debrid.api

import com.seastreams.tv.data.debrid.DebridServiceKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.time.Clock

/**
 * Debrid-Link (ARCHITECTURE §12): `v2/account/infos` for the account; the library is the seedbox torrents
 * (`v2/seedbox/list`, finished files only) plus downloader links (`v2/downloader/list`, unexpired), 50 per page, files
 * included; a playback link is the file's `downloadUrl`, read again by id (`?ids=`) so a deleted item is noticed.
 */
class DebridLinkClient(
    private val http: DebridHttp,
    private val endpoints: DebridEndpoints,
    private val clock: Clock,
) : DebridLibraryClient {
    override val kind = DebridServiceKind.DEBRID_LINK

    override suspend fun account(credential: String): DebridAccount {
        val json = http.get("${endpoints.debridLink}/v2/account/infos", credential).throwOnHttpError().requireJson()
        val value = debridLinkValue(json) as? JsonObject ?: throw DebridServiceException()
        val left = value.long("premiumLeft") ?: 0L
        val premium = (value.long("accountType") ?: 0L) > 0 && left > 0
        return DebridAccount(premium, if (premium) clock.millis() + left * MILLIS_PER_SECOND else null)
    }

    override suspend fun library(credential: String): List<DebridItem> {
        val seedbox = list(credential, SEEDBOX).mapNotNull(::torrent)
        val room = MAX_LIBRARY_ITEMS - seedbox.size
        val links = if (room <= 0) emptyList() else list(credential, DOWNLOADER, room).mapNotNull(::link)
        return seedbox + links
    }

    /** Every page of [section] (`pagination.pages` says how many). */
    private suspend fun list(credential: String, section: String, max: Int = MAX_LIBRARY_ITEMS): List<JsonObject> {
        var pages = 1
        return readPages(PAGE, max) { page ->
            if (page >= pages) return@readPages null
            val json = http.get("${endpoints.debridLink}/v2/$section/list?page=$page&perPage=$PAGE", credential).throwOnHttpError().requireJson()
            pages = json.obj("pagination")?.long("pages")?.toInt() ?: 1
            objects(debridLinkValue(json) as? JsonArray)
        }
    }

    private fun torrent(value: JsonObject): DebridItem? {
        val id = value.str("id")?.takeIf { it.isSafeId() } ?: return null
        val files = objects(value.array("files")).mapNotNull(::file)
        if (files.isEmpty()) return null
        return DebridItem(SEEDBOX_ITEM + id, value.str("name") ?: return null, value.long("totalSize"), files)
    }

    /** A finished seedbox file (`downloadPercent` 100) with its link. */
    private fun file(value: JsonObject): DebridFile? {
        if ((value.long("downloadPercent") ?: 0L) < COMPLETE) return null
        val id = value.str("id") ?: return null
        val name = value.str("name") ?: return null
        return DebridFile(id, name, value.long("size") ?: 0L, value.str("downloadUrl") ?: return null)
    }

    private fun link(value: JsonObject): DebridItem? {
        val id = value.str("id")?.takeIf { it.isSafeId() } ?: return null
        if (value.bool("expired") == true) return null
        val name = value.str("name") ?: return null
        val size = value.long("size") ?: 0L
        val url = value.str("downloadUrl") ?: return null
        return DebridItem(LINK_ITEM + id, name, size, listOf(DebridFile(id, name, size, url)))
    }

    override suspend fun files(credential: String, item: DebridItem): List<DebridFile> = item.files.orEmpty()

    override suspend fun resolve(credential: String, file: DebridFileHandle): String {
        val (section, id) = when {
            file.itemId.startsWith(SEEDBOX_ITEM) -> SEEDBOX to file.itemId.removePrefix(SEEDBOX_ITEM)
            file.itemId.startsWith(LINK_ITEM) -> DOWNLOADER to file.itemId.removePrefix(LINK_ITEM)
            else -> throw DebridNotFoundException()
        }
        if (!id.isSafeId()) throw DebridNotFoundException()
        val json = http.get("${endpoints.debridLink}/v2/$section/list?ids=$id", credential).throwOnMissing().requireJson()
        val item = objects(debridLinkValue(json) as? JsonArray).firstOrNull { it.str("id") == id } ?: throw DebridNotFoundException()
        val found = if (section == SEEDBOX) torrent(item) else link(item)
        val match = found?.files?.firstOrNull { it.id == file.fileId } ?: throw DebridNotFoundException()
        return requireHttps(match.link)
    }

    private companion object {
        const val SEEDBOX = "seedbox"
        const val DOWNLOADER = "downloader"
        const val SEEDBOX_ITEM = "s:"
        const val LINK_ITEM = "d:"
        const val PAGE = 50
        const val COMPLETE = 100L
    }
}
