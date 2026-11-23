package com.seastreams.tv.testutil

import com.seastreams.tv.data.debrid.api.DebridEndpoints
import com.seastreams.tv.data.debrid.api.DebridHttp
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient

/** Every Debrid base URL pointed at one [TestHttpServer], each service under its own path prefix. */
fun TestHttpServer.debridEndpoints() = DebridEndpoints(
    realDebridApi = url("/rd"),
    realDebridOauth = url("/rdoauth"),
    allDebrid = url("/ad"),
    torBox = url("/tb"),
    premiumize = url("/pm"),
    debridLink = url("/dl"),
    offcloud = url("/oc"),
    putIo = url("/putio"),
)

/** Endpoints nobody listens on: every call is *unreachable*. */
val unreachableDebridEndpoints = DebridEndpoints(
    realDebridApi = "http://127.0.0.1:1/rd",
    realDebridOauth = "http://127.0.0.1:1/rdoauth",
    allDebrid = "http://127.0.0.1:1/ad",
    torBox = "http://127.0.0.1:1/tb",
    premiumize = "http://127.0.0.1:1/pm",
    debridLink = "http://127.0.0.1:1/dl",
    offcloud = "http://127.0.0.1:1/oc",
    putIo = "http://127.0.0.1:1/putio",
)

/** A [DebridHttp] on a plain OkHttp client (no 429 retry, no disk cache), as tests need. */
fun testDebridHttp(): DebridHttp = DebridHttp({ OkHttpClient() }, Dispatchers.IO)

suspend fun ApplicationCall.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
    respondText(body, ContentType.Application.Json, status)

/** The bearer token of the request, or `null`. */
fun ApplicationCall.bearer(): String? = request.headers["Authorization"]?.removePrefix("Bearer ")
