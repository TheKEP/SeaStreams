package com.seastreams.tv.data.debrid.api

import com.seastreams.tv.data.debrid.DebridServiceKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

/**
 * Offcloud (ARCHITECTURE §12, after Nabrio `OffcloudApi.cloud` / `cloudFiles`): `account/info` for the account; the
 * library is the cloud history (`cloud/history`, status `downloaded` only); a folder's files come from
 * `cloud/explore/{requestId}` (HTTPS links only); a single-file request's link is its server download URL. The link
 * is the playback link; a folder's is read again on resolve so a deleted request is noticed.
 */
class OffcloudClient(private val http: DebridHttp, private val endpoints: DebridEndpoints) : DebridLibraryClient {
    override val kind = DebridServiceKind.OFFCLOUD

    override suspend fun account(credential: String): DebridAccount {
        val json = http.get("${endpoints.offcloud}/account/info", credential).throwOnHttpError().requireJson()
        if (json.str("error") != null) throw DebridAuthException()
        if (json.str("user_id") == null && json.str("userId") == null) throw DebridServiceException()
        val premium = json.bool("isPremium") ?: json.bool("is_premium") ?: true
        val until = parseTimeMillis(json.str("expirationDate") ?: json.str("expiration_date"))
        return DebridAccount(premium, if (premium) until else null)
    }

    override suspend fun library(credential: String): List<DebridItem> {
        val answer = http.get("${endpoints.offcloud}/cloud/history", credential).throwOnHttpError()
        val history = answer.jsonArray() ?: run {
            if (answer.json()?.str("error") != null) throw DebridAuthException()
            throw DebridServiceException()
        }
        return objects(history).mapNotNull { request ->
            val id = request.str("requestId")?.takeIf { REQUEST_ID.matches(it) } ?: return@mapNotNull null
            if (request.str("status")?.lowercase(Locale.ROOT) != "downloaded") return@mapNotNull null
            val name = request.str("fileName") ?: return@mapNotNull null
            val size = request.long("fileSize") ?: request.long("size")
            val server = request.str("server")?.takeIf { SERVER.matches(it) }
            val files = if (request.bool("isDirectory") == false && server != null) {
                listOf(DebridFile(SINGLE_FILE, name, size ?: 0L, singleFileUrl(server, id, name)))
            } else {
                null
            }
            DebridItem(id, name, size, files)
        }.take(MAX_LIBRARY_ITEMS)
    }

    override suspend fun files(credential: String, item: DebridItem): List<DebridFile> {
        item.files?.let { return it }
        return explore(credential, item.id)
    }

    private suspend fun explore(credential: String, requestId: String): List<DebridFile> {
        if (!REQUEST_ID.matches(requestId)) throw DebridNotFoundException()
        val answer = http.get("${endpoints.offcloud}/cloud/explore/$requestId", credential).throwOnMissing()
        val entries: JsonArray = when (val body = answer.jsonElement()) {
            is JsonArray -> body
            is JsonObject -> body.array("files") ?: throw DebridNotFoundException()
            else -> throw DebridServiceException()
        }
        return entries.mapNotNull { entry ->
            val obj = entry as? JsonObject
            val url = obj?.str("url") ?: (entry as? JsonPrimitive)?.content ?: return@mapNotNull null
            val link = try {
                requireHttps(url)
            } catch (e: DebridServiceException) {
                return@mapNotNull null
            }
            val path = obj?.str("name") ?: obj?.str("path") ?: nameFrom(link) ?: return@mapNotNull null
            DebridFile(path, path.trim('/'), obj?.long("size") ?: 0L, link)
        }.distinctBy { it.link }
    }

    override suspend fun resolve(credential: String, file: DebridFileHandle): String {
        val link = file.link ?: throw DebridNotFoundException()
        // A folder is explored again so a deleted request is noticed here, not by the player.
        if (file.fileId != SINGLE_FILE && explore(credential, file.itemId).none { it.id == file.fileId }) throw DebridNotFoundException()
        return requireHttps(link)
    }

    private fun singleFileUrl(server: String, requestId: String, name: String) =
        "https://$server.offcloud.com/cloud/download/$requestId/" + URLEncoder.encode(name, Charsets.UTF_8.name()).replace("+", "%20")

    private fun nameFrom(link: String): String? = try {
        URI(link).rawPath?.substringAfterLast('/')?.let(::decode)?.takeIf { it.isNotBlank() }
    } catch (e: URISyntaxException) {
        null
    }

    private fun decode(text: String): String = try {
        URLDecoder.decode(text.replace("+", "%2B"), Charsets.UTF_8.name())
    } catch (e: IllegalArgumentException) {
        text
    }

    private companion object {
        /** The file id of a single-file request (its one file). */
        const val SINGLE_FILE = "0"
        val REQUEST_ID = Regex("^[A-Za-z0-9]{6,64}$")
        val SERVER = Regex("^[A-Za-z0-9-]{1,63}$")
    }
}
