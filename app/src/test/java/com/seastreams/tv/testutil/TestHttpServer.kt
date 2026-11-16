package com.seastreams.tv.testutil

import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.path
import io.ktor.server.response.ApplicationResponse
import io.ktor.server.response.header
import io.ktor.server.routing.Routing
import io.ktor.server.routing.routing
import io.ktor.util.toMap
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration

/** A request observed by [TestHttpServer]. */
data class RecordedRequest(
    val method: String,
    val path: String,
    val query: Map<String, List<String>>,
    val headers: Map<String, List<String>>,
) {
    fun queryParam(name: String): String? = query[name]?.firstOrNull()
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
}

/**
 * Real HTTP server (Ktor CIO on an ephemeral localhost port) that records every request, so
 * tests exercise genuine OkHttp caching/retry behaviour over the wire.
 */
class TestHttpServer(routes: Routing.() -> Unit) : AutoCloseable {

    private val recorded = CopyOnWriteArrayList<RecordedRequest>()

    private val server = embeddedServer(CIO, port = 0, host = HOST) {
        install(
            createApplicationPlugin("RequestRecorder") {
                onCall { call ->
                    recorded += RecordedRequest(
                        method = call.request.local.method.value,
                        path = call.request.path(),
                        query = call.request.queryParameters.toMap(),
                        headers = call.request.headers.toMap(),
                    )
                }
            },
        )
        routing(routes)
    }.start(wait = false)

    private val port: Int = runBlocking { server.engine.resolvedConnectors().first().port }

    val baseUrl: String = "http://$HOST:$port"

    val requests: List<RecordedRequest> get() = recorded.toList()

    fun url(path: String): String = baseUrl + path

    fun hits(path: String): Int = recorded.count { it.path == path }

    override fun close() {
        server.stop(gracePeriodMillis = 0, timeoutMillis = 0)
    }

    companion object {
        private const val HOST = "127.0.0.1"
        private val HTTP_DATE: DateTimeFormatter = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC)

        /** Formats an RFC 1123 `Date` header value [age] in the past. */
        fun httpDateAgo(age: Duration): String =
            HTTP_DATE.format(Instant.now().minusMillis(age.inWholeMilliseconds))
    }
}

/** Marks the response as generated [age] ago, simulating a cache entry of that age. */
fun ApplicationResponse.dateAgo(age: Duration) {
    header("Date", TestHttpServer.httpDateAgo(age))
}
