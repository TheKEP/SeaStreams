package com.seastreams.tv.core.webserver

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.serializer

/**
 * Usenet Sources endpoints under `/api` (PRD §12), with the same trusted-host and JSON
 * content-type guards as every other portal route:
 * - `GET /api/usenet` – [UsenetPortalState] (secrets masked)
 * - `POST /api/usenet/providers[/remove|/enabled|/move|/test]`, `/api/usenet/indexers[/…]`
 * - `POST /api/usenet/headers`, `/api/usenet/headers/update`, `/api/usenet/performance`
 */
internal fun Route.usenetRoutes(controller: PortalUsenetController) {
    get("/usenet") {
        if (!call.hasTrustedHost()) return@get call.respondForbiddenHost()
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respondJson(HttpStatusCode.OK, controller.state(), serializer())
    }
    post("/usenet/providers") { call.mutate<ProviderRequest>(controller::saveProvider) }
    post("/usenet/providers/remove") { call.mutate<SourceIdRequest>(controller::removeProvider) }
    post("/usenet/providers/enabled") { call.mutate<SourceEnabledRequest>(controller::setProviderEnabled) }
    post("/usenet/providers/move") { call.mutate<SourceMoveRequest>(controller::moveProvider) }
    post("/usenet/providers/test") { call.mutate<ProviderRequest>(controller::testProvider) }
    post("/usenet/indexers") { call.mutate<IndexerRequest>(controller::saveIndexer) }
    post("/usenet/indexers/remove") { call.mutate<SourceIdRequest>(controller::removeIndexer) }
    post("/usenet/indexers/enabled") { call.mutate<SourceEnabledRequest>(controller::setIndexerEnabled) }
    post("/usenet/indexers/move") { call.mutate<SourceMoveRequest>(controller::moveIndexer) }
    post("/usenet/headers") { call.mutate<HeadersRequest>(controller::setHeaders) }
    post("/usenet/headers/update") { call.mutateWithoutBody(controller::updateHeaders) }
    post("/usenet/performance") { call.mutate<PerformanceRequest>(controller::setPerformance) }
}
