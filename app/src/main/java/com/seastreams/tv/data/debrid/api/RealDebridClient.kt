package com.seastreams.tv.data.debrid.api

import com.seastreams.tv.data.debrid.DebridServiceKind
import kotlinx.serialization.json.JsonPrimitive

/**
 * Real-Debrid (ARCHITECTURE §12): `GET /user` for the account; the library is the finished torrents
 * (`GET /torrents`, status `downloaded`) plus the download history (`GET /downloads`, minus links that belong to a
 * listed torrent); `GET /torrents/info/{id}` pairs the selected files with the torrent's `links`; a playback link is
 * `POST /unrestrict/link`.
 */
class RealDebridClient(private val http: DebridHttp, private val endpoints: DebridEndpoints) : DebridLibraryClient {
    override val kind = DebridServiceKind.REAL_DEBRID

    override suspend fun account(credential: String): DebridAccount {
        val json = http.get("${endpoints.realDebridApi}/user", credential).throwOnHttpError().requireJson()
        if (json.long("id") == null) throw DebridServiceException()
        val premium = json.str("type") == "premium"
        return DebridAccount(premium, if (premium) parseTimeMillis(json.str("expiration")) else null)
    }

    override suspend fun library(credential: String): List<DebridItem> {
        val torrentLinks = HashSet<String>()
        val torrents = readPages(PAGE) { page -> listPage("torrents", page, credential) }.mapNotNull { torrent ->
            torrent.array("links")?.mapNotNull { (it as? JsonPrimitive)?.content }?.let(torrentLinks::addAll)
            val id = torrent.str("id") ?: return@mapNotNull null
            if (torrent.str("status") != "downloaded") return@mapNotNull null
            DebridItem(TORRENT + id, torrent.str("filename") ?: return@mapNotNull null, torrent.long("bytes"))
        }
        val room = MAX_LIBRARY_ITEMS - torrents.size
        val downloads = if (room <= 0) emptyList() else readPages(PAGE, room) { page -> listPage("downloads", page, credential) }.mapNotNull { download ->
            val id = download.str("id") ?: return@mapNotNull null
            val link = download.str("link") ?: return@mapNotNull null
            if (link in torrentLinks) return@mapNotNull null
            val name = download.str("filename") ?: return@mapNotNull null
            val size = download.long("filesize") ?: 0L
            DebridItem(DOWNLOAD + id, name, size, files = listOf(DebridFile(id, name, size, link)))
        }
        return torrents + downloads
    }

    /** One page of `/torrents` or `/downloads` (1-based on the wire); an empty body (`204`) ends the listing. */
    private suspend fun listPage(path: String, page: Int, credential: String) =
        http.get("${endpoints.realDebridApi}/$path?page=${page + 1}&limit=$PAGE", credential).throwOnHttpError()
            .let { answer -> if (answer.body.isBlank()) emptyList() else objects(answer.jsonArray() ?: throw DebridServiceException()) }

    override suspend fun files(credential: String, item: DebridItem): List<DebridFile> {
        item.files?.let { return it }
        val id = item.id.removePrefix(TORRENT).takeIf { item.id.startsWith(TORRENT) && it.isSafeId() } ?: return emptyList()
        return torrentFiles(credential, id)
    }

    /** The selected files of torrent [id], each with its link (RD lists one link per selected file, in file order). */
    private suspend fun torrentFiles(credential: String, id: String): List<DebridFile> {
        val info = http.get("${endpoints.realDebridApi}/torrents/info/$id", credential).throwOnMissing().requireJson()
        if (info.str("status") != null && info.str("status") != "downloaded") throw DebridNotFoundException()
        val links = info.array("links")?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
        val selected = objects(info.array("files")).filter { it.long("selected") == 1L }.sortedBy { it.long("id") ?: 0L }
        // A torrent RD packed into one archive has fewer links than files: its files can't be played one by one.
        if (selected.size != links.size) return emptyList()
        return selected.mapIndexedNotNull { index, file ->
            val fileId = file.long("id")?.toString() ?: return@mapIndexedNotNull null
            val path = file.str("path")?.trimStart('/') ?: return@mapIndexedNotNull null
            DebridFile(fileId, path, file.long("bytes") ?: 0L, links[index])
        }
    }

    override suspend fun resolve(credential: String, file: DebridFileHandle): String {
        val link = if (file.itemId.startsWith(TORRENT)) {
            // Read again so a deleted torrent is noticed here, not by the player.
            val id = file.itemId.removePrefix(TORRENT).takeIf { it.isSafeId() } ?: throw DebridNotFoundException()
            torrentFiles(credential, id).firstOrNull { it.id == file.fileId }?.link ?: throw DebridNotFoundException()
        } else {
            file.link ?: throw DebridNotFoundException()
        }
        val answer = http.postForm("${endpoints.realDebridApi}/unrestrict/link", mapOf("link" to link), credential).throwOnMissing()
        val json = answer.requireJson()
        if (json.str("error") != null) throw realDebridError(json.long("error_code"))
        return requireHttps(json.str("download"))
    }

    private fun realDebridError(code: Long?): DebridException = when (code) {
        // 8 bad token, 9 permission denied, 14 account locked, 20 premium only.
        8L, 9L, 14L, 20L -> DebridAuthException()
        // 7 unknown resource, 16 unsupported hoster, 19 hoster unavailable for free, 24 unavailable file, 35 infringing.
        7L, 16L, 19L, 24L, 35L -> DebridNotFoundException()
        34L -> DebridRateLimitedException()
        else -> DebridServiceException()
    }

    private companion object {
        const val TORRENT = "t:"
        const val DOWNLOAD = "d:"
        const val PAGE = 500
    }
}

/** Ids that may be put in a path segment as they are. */
internal fun String.isSafeId(): Boolean = isNotEmpty() && length <= MAX_ID_CHARS && all { it.isLetterOrDigit() || it == '-' || it == '_' }

private const val MAX_ID_CHARS = 128
