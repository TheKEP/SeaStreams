package com.seastreams.tv.core.webserver

import com.seastreams.tv.data.UserFacingException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.serializer

/**
 * Stream filtering, sorting and formatter endpoints under `/api` (PRD §13), with the same trusted-host and JSON
 * content-type guards as every other portal route:
 * - `GET /api/streams?profileId=…` – [StreamPortalState]
 * - `POST /api/streams/filters`, `/api/streams/sort`, `/api/streams/formatter` – save one profile's settings ([ApiMessage])
 * - `POST /api/streams/format-preview` – render templates on the sample release ([FormatPreviewResponse])
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
}

/** Like [guarded], but answers with the rendered preview instead of an [ApiMessage]. */
private suspend fun ApplicationCall.formatPreview(controller: PortalStreamController) {
    if (!hasTrustedHost()) return respondForbiddenHost()
    if (!request.contentType().match(ContentType.Application.Json)) {
        return respondJson(HttpStatusCode.UnsupportedMediaType, ApiMessage(false, "Expected application/json"), serializer())
    }
    val body = receiveText()
    if (body.length > MAX_BODY_CHARS) return respondJson(HttpStatusCode.PayloadTooLarge, ApiMessage(false, "Request too large"), serializer())
    val request = try {
        portalJson.decodeFromString(serializer<FormatPreviewRequest>(), body)
    } catch (e: SerializationException) {
        return respondJson(HttpStatusCode.BadRequest, ApiMessage(false, "Malformed request"), serializer())
    } catch (e: IllegalArgumentException) {
        return respondJson(HttpStatusCode.BadRequest, ApiMessage(false, "Malformed request"), serializer())
    }
    try {
        respondJson(HttpStatusCode.OK, controller.preview(request), serializer())
    } catch (e: CancellationException) {
        throw e
    } catch (e: RuntimeException) {
        respondJson(HttpStatusCode.BadRequest, ApiMessage(false, "That template couldn't be rendered"), serializer())
    }
}
