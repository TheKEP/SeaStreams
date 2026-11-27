package com.seastreams.tv.core.webserver

import com.seastreams.tv.data.UserFacingException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.serializer

/**
 * Stream filtering, sorting and formatter endpoints under `/api` (PRD §13), with the same trusted-host and JSON
 * content-type guards as every other portal route:
 * - `GET /api/streams?profileId=…` – [StreamPortalState]
 * - `POST /api/streams/filters`, `/api/streams/sort`, `/api/streams/formatter` – save one profile's rules and/or that
 *   stage's master toggle (`filteringEnabled` / `sortingEnabled` / `formattingEnabled`) ([ApiMessage])
 * - `POST /api/streams/format-preview` – render templates on the sample release ([FormatPreviewResponse])
 * - `GET /api/streams/export?profileId=…` – the profile's stages as a JSON file download ([StreamBackupFile])
 * - `POST /api/streams/import?profileId=…` – the body is such a file; it is validated, then replaces all three stages
 */
internal fun Route.streamRoutes(controller: PortalStreamController) {
    get("/streams") {
        if (!call.hasTrustedHost()) return@get call.respondForbiddenHost()
        call.response.header(HttpHeaders.CacheControl, "no-store")
        val profileId = call.request.queryParameters["profileId"]?.toLongOrNull()
            ?: return@get call.respondJson(HttpStatusCode.BadRequest, ApiMessage(false, "Pick a profile"), serializer())
        try {
            call.respondJson(HttpStatusCode.OK, controller.state(profileId), serializer())
        } catch (e: UserFacingException) {
            call.respondJson(HttpStatusCode.NotFound, ApiMessage(false, e.message.orEmpty()), serializer())
        }
    }
    post("/streams/filters") { call.mutate<StreamFiltersRequest>(controller::saveFilters) }
    post("/streams/sort") { call.mutate<StreamSortRequest>(controller::saveSort) }
    post("/streams/formatter") { call.mutate<StreamFormatterRequest>(controller::saveFormatter) }
    post("/streams/format-preview") { call.formatPreview(controller) }
    get("/streams/export") {
        if (!call.hasTrustedHost()) return@get call.respondForbiddenHost()
        call.response.header(HttpHeaders.CacheControl, "no-store")
        val profileId = call.request.queryParameters["profileId"]?.toLongOrNull()
            ?: return@get call.respondJson(HttpStatusCode.BadRequest, ApiMessage(false, "Pick a profile"), serializer())
        val file = try {
            controller.exportBackup(profileId)
        } catch (e: UserFacingException) {
            return@get call.respondJson(HttpStatusCode.NotFound, ApiMessage(false, e.message.orEmpty()), serializer())
        }
        // The slug holds only a-z, 0-9 and '-', so the quoted file name needs no escaping.
        call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"${file.fileName}\"")
        call.respondText(file.json, ContentType.Application.Json)
    }
    post("/streams/import") {
        call.guarded {
            val profileId = call.request.queryParameters["profileId"]?.toLongOrNull() ?: throw UserFacingException("Pick a profile")
            val body = call.receiveBoundedText(MAX_BODY_CHARS)
            controller.importBackup(profileId, body)
        }
    }
}

/** Like [guarded], but answers with the rendered preview instead of an [ApiMessage]. */
private suspend fun ApplicationCall.formatPreview(controller: PortalStreamController) {
    if (!acceptsJsonPost()) return
    val request = try {
        receiveJson(serializer<FormatPreviewRequest>())
    } catch (e: UserFacingException) {
        // As in [guarded]: a body over the limit without a declared length is a 400 like any other bad body.
        return respondJson(HttpStatusCode.BadRequest, ApiMessage(false, e.message.orEmpty()), serializer())
    }
    try {
        respondJson(HttpStatusCode.OK, controller.preview(request), serializer())
    } catch (e: CancellationException) {
        throw e
    } catch (e: RuntimeException) {
        respondJson(HttpStatusCode.BadRequest, ApiMessage(false, "That template couldn't be rendered"), serializer())
    }
}
