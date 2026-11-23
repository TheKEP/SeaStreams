package com.seastreams.tv.core.webserver

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.serializer

/**
 * Debrid account endpoints under `/api` (PRD §14), with the same trusted-host and JSON content-type guards as every
 * other portal route. No response carries a key or token:
 * - `GET /api/debrid` – [DebridPortalState]
 * - `POST /api/debrid/providers[/remove|/enabled|/move|/test]`
 * - `POST /api/debrid/auth/start` → [DebridAuthSessionDto]; `GET /api/debrid/auth/{session}`; `POST /api/debrid/auth/cancel`
 * - `POST /api/sources/toggles` – `Enable Usenet` / `Enable Debrid`
 */
internal fun Route.debridRoutes(controller: PortalDebridController) {
    get("/debrid") {
        if (!call.hasTrustedHost()) return@get call.respondForbiddenHost()
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respondJson(HttpStatusCode.OK, controller.state(), serializer())
    }
    post("/debrid/providers") { call.mutate<DebridProviderRequest>(controller::saveProvider) }
    post("/debrid/providers/remove") { call.mutate<SourceIdRequest>(controller::removeProvider) }
    post("/debrid/providers/enabled") { call.mutate<SourceEnabledRequest>(controller::setProviderEnabled) }
    post("/debrid/providers/move") { call.mutate<SourceMoveRequest>(controller::moveProvider) }
    post("/debrid/providers/test") { call.mutate<DebridTestRequest>(controller::test) }
    post("/debrid/auth/start") {
        call.guardedJson(serializer()) { controller.startAuth(call.receiveJson(serializer<DebridAuthStartRequest>())) }
    }
    post("/debrid/auth/cancel") { call.mutate<DebridAuthCancelRequest> { controller.cancelAuth(it) } }
    get("/debrid/auth/{session}") {
        if (!call.hasTrustedHost()) return@get call.respondForbiddenHost()
        call.response.header(HttpHeaders.CacheControl, "no-store")
        val id = call.parameters["session"].orEmpty()
        val session = id.takeIf(SESSION_ID::matches)?.let(controller::authStatus)
            ?: return@get call.respondJson(HttpStatusCode.NotFound, ApiMessage(false, "That sign-in has ended. Start again"), serializer())
        call.respondJson(HttpStatusCode.OK, session, serializer())
    }
    post("/sources/toggles") { call.mutate<SourceTogglesRequest>(controller::setToggles) }
}

private val SESSION_ID = Regex("[0-9a-f]{32}")
