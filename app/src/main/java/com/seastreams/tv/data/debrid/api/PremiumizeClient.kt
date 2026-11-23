package com.seastreams.tv.data.debrid.api

import com.seastreams.tv.data.debrid.DebridServiceKind
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder
import java.time.Clock
import java.util.Locale

/**
 * Premiumize (ARCHITECTURE §12): `account/info` for the account; the library is every cloud file
 * (`item/listall`, one item per file named by its full path so the matcher sees its folders) plus finished transfers
 * whose file isn't listed yet (`transfer/list`); a playback link is `item/details` → `link` (else `stream_link`).
 * Premiumize only takes the key as the `apikey` query parameter; those URLs are never logged or stored.
 */
class PremiumizeClient(
    private val http: DebridHttp,
    private val endpoints: DebridEndpoints,
    private val clock: Clock,
) : DebridLibraryClient {
    override val kind = DebridServiceKind.PREMIUMIZE

    override suspend fun account(credential: String): DebridAccount {
        val json = http.get(url("account/info", credential)).throwOnHttpError().requireJson()
        if (json.str("status") != "success") throw DebridAuthException()
        val until = json.long("premium_until")?.let(::epochToMillis)
        val premium = until != null && until > clock.millis()
        return DebridAccount(premium, if (premium) until else null)
    }

    override suspend fun library(credential: String): List<DebridItem> {
        val listed = success(http.get(url("item/listall", credential)).throwOnHttpError().requireJson())
        val files = objects(listed.array("files")).mapNotNull { file ->
            val id = file.str("id")?.takeIf { it.isSafeId() } ?: return@mapNotNull null
            val name = file.str("name") ?: return@mapNotNull null
            // `path` is the file's full path; tolerate one that names only the folder.
            val folder = file.str("path")?.trim('/').orEmpty()
            val path = when {
                folder.isEmpty() -> name
                folder == name || folder.endsWith("/$name") -> folder
                else -> "$folder/$name"
            }
            val size = file.long("size") ?: 0L
            DebridItem(id, path, size, files = listOf(DebridFile(id, path, size)))
        }.take(MAX_LIBRARY_ITEMS)
        val room = MAX_LIBRARY_ITEMS - files.size
        if (room <= 0) return files
        val known = files.mapTo(HashSet()) { it.id }
        val transfers = success(http.get(url("transfer/list", credential)).throwOnHttpError().requireJson())
        val finished = objects(transfers.array("transfers")).mapNotNull { transfer ->
            if (transfer.str("status")?.lowercase(Locale.ROOT) != "finished") return@mapNotNull null
            val fileId = transfer.str("file_id")?.takeIf { it.isSafeId() && it !in known } ?: return@mapNotNull null
            val name = transfer.str("name") ?: return@mapNotNull null
            DebridItem(fileId, name, null, files = listOf(DebridFile(fileId, name, 0L)))
        }.take(room)
        return files + finished
    }

    override suspend fun files(credential: String, item: DebridItem): List<DebridFile> = item.files.orEmpty()

    override suspend fun resolve(credential: String, file: DebridFileHandle): String {
        val id = file.fileId.takeIf { it.isSafeId() } ?: throw DebridNotFoundException()
        val details = success(http.get(url("item/details", credential) + "&id=$id").throwOnMissing().requireJson())
        return requireHttps(details.str("link") ?: details.str("stream_link"))
    }

    /** Premiumize answers errors as `status: error` + a message. */
    private fun success(json: JsonObject): JsonObject {
        // item/details answers the item itself, without a status, when it exists.
        if (json.str("status") == null && json.str("id") != null) return json
        if (json.str("status") == "success") return json
        val message = json.str("message")?.lowercase(Locale.ROOT).orEmpty()
        throw when {
            "not found" in message || "does not exist" in message -> DebridNotFoundException()
            "log" in message || "auth" in message || "apikey" in message || "customer" in message -> DebridAuthException()
            else -> DebridServiceException()
        }
    }

    private fun url(path: String, credential: String) =
        "${endpoints.premiumize}/$path?apikey=" + URLEncoder.encode(credential, Charsets.UTF_8.name())
}
