package com.seastreams.tv.data.debrid.api

import com.seastreams.tv.data.debrid.DebridServiceKind
import kotlinx.serialization.json.JsonObject
import java.util.Locale

/**
 * put.io (ARCHITECTURE §12): `account/info` for the account; the library is the top level of the user's files
 * (`files/list?parent_id=0`, folders and videos, continued with `files/list/continue`); a folder's files are listed
 * folder by folder (`files/list?parent_id=`, nested folders followed); a playback link is `files/{id}/url`.
 * put.io only stores finished files, so nothing needs a status filter.
 */
class PutIoClient(private val http: DebridHttp, private val endpoints: DebridEndpoints) : DebridLibraryClient {
    override val kind = DebridServiceKind.PUT_IO

    override suspend fun account(credential: String): DebridAccount {
        val answer = http.get("${endpoints.putIo}/account/info", credential)
        if (answer.code == HTTP_BAD_REQUEST) throw DebridAuthException()
        val json = answer.throwOnHttpError().requireJson()
        val info = json.obj("info")
        if (json.str("status") != "OK" || info == null) throw DebridAuthException()
        val premium = info.bool("account_active") == true
        return DebridAccount(premium, if (premium) parseTimeMillis(info.str("plan_expiration_date")) else null)
    }

    override suspend fun library(credential: String): List<DebridItem> = children(credential, ROOT, MAX_LIBRARY_ITEMS).mapNotNull { file ->
        val id = file.str("id")?.takeIf { it.isSafeId() } ?: return@mapNotNull null
        val name = file.str("name") ?: return@mapNotNull null
        val size = file.long("size") ?: 0L
        when {
            file.str("file_type") == FOLDER -> DebridItem(id, name, size)
            else -> videoFile(file, id, prefix = "")?.let { DebridItem(id, it.path, size, listOf(it)) }
        }
    }

    override suspend fun files(credential: String, item: DebridItem): List<DebridFile> {
        item.files?.let { return it }
        if (!item.id.isSafeId()) return emptyList()
        val found = ArrayList<DebridFile>()
        walk(credential, item.id, prefix = "", depth = 0, into = found)
        return found
    }

    /** Videos under folder [id], following sub-folders up to [MAX_DEPTH] levels and [MAX_LIBRARY_ITEMS] files. */
    private suspend fun walk(credential: String, id: String, prefix: String, depth: Int, into: MutableList<DebridFile>) {
        for (file in children(credential, id, MAX_LIBRARY_ITEMS - into.size)) {
            if (into.size >= MAX_LIBRARY_ITEMS) return
            val fileId = file.str("id")?.takeIf { it.isSafeId() } ?: continue
            if (file.str("file_type") == FOLDER) {
                val name = file.str("name") ?: continue
                if (depth < MAX_DEPTH) walk(credential, fileId, if (prefix.isEmpty()) name else "$prefix/$name", depth + 1, into)
            } else {
                videoFile(file, fileId, prefix)?.let { into += it }
            }
        }
    }

    /**
     * A video entry as a [DebridFile], or `null`. put.io types files it hasn't identified (often x265 / 10-bit MKVs)
     * as `FILE`, not `VIDEO`, so a file also counts when its `content_type` starts with `video/` or its extension is a video
     * one. A name without that extension gets put.io's `extension` appended, so later video checks see it.
     */
    private fun videoFile(file: JsonObject, id: String, prefix: String): DebridFile? {
        val name = file.str("name") ?: return null
        val extension = file.str("extension")?.lowercase(Locale.ROOT)?.trimStart('.')
        val typed = file.str("file_type") == VIDEO || file.str("content_type")?.lowercase(Locale.ROOT)?.startsWith("video/") == true
        val named = isVideoFileName(name)
        val byExtension = extension != null && isVideoFileName(".$extension")
        if (!typed && !named && !byExtension) return null
        val shown = if (!named && extension != null && byExtension) "$name.$extension" else name
        return DebridFile(id, if (prefix.isEmpty()) shown else "$prefix/$shown", file.long("size") ?: 0L)
    }

    /** Every entry of folder [parent] (cursor-paged), at most [max]. */
    private suspend fun children(credential: String, parent: String, max: Int): List<JsonObject> {
        val first = http.get("${endpoints.putIo}/files/list?parent_id=$parent&per_page=$PAGE", credential).throwOnMissing().requireJson()
        val entries = objects(first.array("files")).take(max).toMutableList()
        var cursor = first.str("cursor")
        while (cursor != null && entries.size < max) {
            // As put.io's own client sends it: the cursor in the body, `per_page` in the query.
            val next = http.postForm("${endpoints.putIo}/files/list/continue?per_page=$PAGE", mapOf("cursor" to cursor), credential)
                .throwOnHttpError().requireJson()
            val page = objects(next.array("files"))
            if (page.isEmpty()) break
            entries += page.take(max - entries.size)
            cursor = next.str("cursor")
        }
        return entries
    }

    override suspend fun resolve(credential: String, file: DebridFileHandle): String {
        val id = file.fileId.takeIf { it.isSafeId() } ?: throw DebridNotFoundException()
        val json = http.get("${endpoints.putIo}/files/$id/url", credential).throwOnMissing().requireJson()
        return requireHttps(json.str("url"))
    }

    private companion object {
        const val ROOT = "0"
        const val FOLDER = "FOLDER"
        const val VIDEO = "VIDEO"
        const val PAGE = 1_000
        const val MAX_DEPTH = 4
    }
}
