package com.seastreams.tv.core.webserver

import android.util.Log
import com.seastreams.tv.data.UserFacingException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.host
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

internal val portalJson = Json {
    ignoreUnknownKeys = true
    // Responses always carry every field (e.g. performance values equal to their defaults).
    encodeDefaults = true
}

internal const val MAX_BODY_CHARS = 64 * 1024
private const val TAG = "LocalPortal"

/**
 * Portal HTTP surface:
 * - `GET /` – the single-page portal ([PortalHtmlTemplate])
 * - `GET /api/state` – [PortalState]
 * - `POST /api/…` – JSON mutations, each answering [ApiMessage]
 * - `/api/usenet…` – app-wide Usenet Sources ([usenetRoutes])
 * - `/api/debrid…`, `/api/sources/toggles` – app-wide Debrid accounts and the source toggles ([debridRoutes])
 * - `/api/streams…` – per-profile stream filters, sorting and formatter ([streamRoutes])
 * - `GET/HEAD /nntp/{token}/{name}` – the loopback-only Usenet proxy for external players ([nntpProxyRoutes])
 *
 * Mutations require `Content-Type: application/json`, which browsers cannot send cross-origin
 * without a CORS preflight this server never approves (CSRF protection). Requests whose `Host` is
 * not an IP literal or `localhost` are refused, which defeats DNS-rebinding attacks.
 */
fun Application.portalModule(
    controller: PortalController,
    usenet: PortalUsenetController,
    proxy: NntpProxy? = null,
    streams: PortalStreamController? = null,
    debrid: PortalDebridController? = null,
) {
    routing {
        proxy?.let { nntpProxyRoutes(it) }
        get("/") {
            if (!call.hasTrustedHost()) return@get call.respondForbiddenHost()
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respondText(PortalHtmlTemplate.render(), ContentType.Text.Html)
        }
        route("/api") {
            get("/state") {
                if (!call.hasTrustedHost()) return@get call.respondForbiddenHost()
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.respondJson(HttpStatusCode.OK, controller.state(), serializer())
            }
            post("/tmdb-key") { call.mutate<TmdbKeyRequest>(controller::saveTmdbKey) }
            post("/profiles") { call.mutate<ProfileRequest>(controller::createProfile) }
            post("/profiles/{id}") {
                val id = call.parameters["id"]?.toLongOrNull()
                call.mutate<ProfileRequest> { controller.updateProfile(requireId(id), it) }
            }
            post("/profiles/{id}/delete") {
                val id = call.parameters["id"]?.toLongOrNull()
                call.mutateWithoutBody { controller.deleteProfile(requireId(id)) }
            }
            post("/addons") { call.mutate<AddonUrlRequest>(controller::installAddon) }
            post("/addons/remove") { call.mutate<AddonUrlRequest>(controller::removeAddon) }
            post("/addons/rename") { call.mutate<AddonRenameRequest>(controller::renameAddon) }
            post("/addons/enabled") { call.mutate<AddonEnabledRequest>(controller::setAddonEnabled) }
            post("/addons/move") { call.mutate<AddonMoveRequest>(controller::moveAddon) }
            post("/addons/reorder") { call.mutate<AddonReorderRequest>(controller::reorderAddons) }
            post("/library/import") { call.mutate<LibraryImportRequest>(controller::importToLibrary) }
            usenetRoutes(usenet)
            streams?.let { streamRoutes(it) }
            debrid?.let { debridRoutes(it) }
        }
    }
}

private fun requireId(id: Long?): Long = id ?: throw UserFacingException("Invalid id")

internal suspend inline fun <reified T> ApplicationCall.mutate(noinline action: suspend (T) -> String) =
    mutateWithBody(serializer<T>(), action)

internal suspend fun ApplicationCall.mutateWithoutBody(action: suspend () -> String) =
    guarded { action() }

internal suspend fun <T> ApplicationCall.mutateWithBody(deserializer: KSerializer<T>, action: suspend (T) -> String) =
    guarded { action(receiveJson(deserializer)) }

/** Reads and decodes a JSON body of at most [MAX_BODY_CHARS]; a bad body throws a [UserFacingException]. */
internal suspend fun <T> ApplicationCall.receiveJson(deserializer: KSerializer<T>): T {
    val body = receiveText()
    if (body.length > MAX_BODY_CHARS) throw UserFacingException("Request too large")
    return try {
        portalJson.decodeFromString(deserializer, body)
    } catch (e: SerializationException) {
        throw UserFacingException("Malformed request")
    }
}

/**
 * The trusted-host, JSON content-type and declared-size checks every JSON POST passes; when one fails it answers the
 * call itself and returns `false`.
 */
internal suspend fun ApplicationCall.acceptsJsonPost(): Boolean {
    if (!hasTrustedHost()) {
        respondForbiddenHost()
        return false
    }
    if (!request.contentType().match(ContentType.Application.Json)) {
        respondJson(HttpStatusCode.UnsupportedMediaType, ApiMessage(false, "Expected application/json"), serializer())
        return false
    }
    val declaredLength = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    if (declaredLength != null && declaredLength > MAX_BODY_CHARS) {
        respondJson(HttpStatusCode.PayloadTooLarge, ApiMessage(false, "Request too large"), serializer())
        return false
    }
    return true
}

/** Shared checks + error mapping for every mutation. */
internal suspend fun ApplicationCall.guarded(action: suspend () -> String) {
    if (!acceptsJsonPost()) return
    val (status, message) = try {
        HttpStatusCode.OK to ApiMessage(true, action())
    } catch (e: CancellationException) {
        throw e
    } catch (e: UserFacingException) {
        HttpStatusCode.BadRequest to ApiMessage(false, e.message.orEmpty())
    } catch (e: Exception) {
        logError("Portal request ${request.local.uri} failed", e)
        HttpStatusCode.InternalServerError to ApiMessage(false, "Something went wrong on the TV. Please try again.")
    }
    respondJson(status, message, serializer())
}

internal suspend fun <T> ApplicationCall.respondJson(status: HttpStatusCode, body: T, serializer: KSerializer<T>) =
    respondText(portalJson.encodeToString(serializer, body), ContentType.Application.Json, status)

internal suspend fun ApplicationCall.respondForbiddenHost() =
    respondJson(HttpStatusCode.Forbidden, ApiMessage(false, "Open the portal using the TV's IP address"), serializer())

/** Accepts IPv4/IPv6 literals and `localhost`; rejects DNS names (DNS-rebinding defence). */
internal fun ApplicationCall.hasTrustedHost(): Boolean = isTrustedPortalHost(request.host())

internal fun isTrustedPortalHost(host: String): Boolean {
    val bare = host.removePrefix("[").removeSuffix("]")
    if (bare.equals("localhost", ignoreCase = true)) return true
    val octets = bare.split('.')
    if (octets.size == 4 && octets.all { o -> o.toIntOrNull()?.let { it in 0..255 } == true && o.length <= 3 }) return true
    return bare.contains(':') && bare.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' || it == '%' }
}

private fun logError(message: String, error: Throwable) {
    // android.util.Log is stubbed (returns defaults) in JVM unit tests, so this is safe there too.
    Log.e(TAG, message, error)
}
