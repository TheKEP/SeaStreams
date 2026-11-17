package com.seastreams.tv.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Multi-connection HTTP byte-range [DataSource] (PRD §9): splits sequential reads of an HTTP(S)
 * stream into concurrent `Range: bytes=start-end` requests of [chunkSize] bytes via
 * [ParallelRangeReader]. It wraps an [OkHttpDataSource], which takes over transparently when the
 * reader can't be used: one connection configured, a non-HTTP URI, a non-GET request, or a server
 * that doesn't answer the first ranged request with `206 Partial Content`. Such hosts are remembered
 * in [singleStreamHosts], so later opens (e.g. every HLS segment) skip the probe.
 */
class ParallelRangeDataSource(
    private val client: OkHttpClient,
    private val upstream: HttpDataSource,
    private val connections: Int,
    private val chunkSize: Int,
    private val defaultHeaders: Map<String, String>,
    private val meter: ThroughputMeter?,
    private val singleStreamHosts: MutableSet<String> = ConcurrentHashMap.newKeySet(),
) : BaseDataSource(true) {

    private var reader: ParallelRangeReader? = null
    private var upstreamOpen = false
    private var uri: Uri? = null

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val parallel = if (canSplit(dataSpec)) openParallel(dataSpec) else null
        if (parallel != null) {
            reader = parallel
            transferStarted(dataSpec)
            return parallel.remaining
        }
        upstreamOpen = true
        val length = upstream.open(dataSpec)
        transferStarted(dataSpec)
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val active = reader
        val read = if (active != null) active.read(buffer, offset, length) else upstream.read(buffer, offset, length)
        if (read == C.RESULT_END_OF_INPUT) return C.RESULT_END_OF_INPUT
        if (read > 0) {
            if (active == null) meter?.record(read)
            bytesTransferred(read)
        }
        return read
    }

    override fun getUri(): Uri? = uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        if (upstreamOpen) upstream.responseHeaders else emptyMap()

    override fun close() {
        val active = reader
        val wasOpen = active != null || upstreamOpen
        reader = null
        try {
            active?.close()
            if (upstreamOpen) upstream.close()
        } finally {
            upstreamOpen = false
            uri = null
            if (wasOpen) transferEnded()
        }
    }

    private fun canSplit(dataSpec: DataSpec): Boolean {
        val scheme = dataSpec.uri.scheme?.lowercase()
        return connections > 1 && (scheme == "http" || scheme == "https") && dataSpec.httpMethod == DataSpec.HTTP_METHOD_GET &&
            dataSpec.uri.host !in singleStreamHosts
    }

    /** Probes with the first chunk's range; `null` falls back to the single-stream upstream. */
    private fun openParallel(dataSpec: DataSpec): ParallelRangeReader? {
        val headers = defaultHeaders + dataSpec.httpRequestHeaders
        val fetcher = OkHttpRangeFetcher(client, dataSpec.uri.toString(), headers)
        val probeEnd = dataSpec.position + chunkSize - 1
        val probe = fetcher.probe(dataSpec.position, probeEnd)
        if (probe == null) {
            dataSpec.uri.host?.let(singleStreamHosts::add)
            return null
        }
        val requestedEnd = if (dataSpec.length == C.LENGTH_UNSET.toLong()) Long.MAX_VALUE else dataSpec.position + dataSpec.length
        val end = minOf(probe.totalLength, requestedEnd)
        if (end <= dataSpec.position) {
            probe.connection.close()
            return null
        }
        return ParallelRangeReader(
            fetcher = fetcher,
            start = dataSpec.position,
            end = end,
            chunkSize = chunkSize,
            connections = connections,
            executor = RangeExecutor.instance,
            meter = meter,
            firstConnection = probe.connection,
        )
    }

    class Factory(
        private val client: OkHttpClient,
        private val connections: Int,
        private val meter: ThroughputMeter?,
        private val chunkSize: Int = ParallelRangeReader.DEFAULT_CHUNK_SIZE,
    ) : DataSource.Factory {
        private var headers: Map<String, String> = emptyMap()
        private val singleStreamHosts: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /** Request headers every request of this stream needs (addon `proxyHeaders`). */
        fun setDefaultRequestProperties(properties: Map<String, String>): Factory = apply { headers = properties }

        override fun createDataSource(): DataSource {
            val upstream = OkHttpDataSource.Factory(client).setDefaultRequestProperties(headers).createDataSource()
            return ParallelRangeDataSource(client, upstream, connections, chunkSize, headers, meter, singleStreamHosts)
        }
    }
}

/** A successful ranged probe: the open first chunk and the resource's total length. */
private class RangeProbe(val connection: RangeConnection, val totalLength: Long)

/** [RangeFetcher] issuing plain OkHttp calls, cancellable from any thread via [Call.cancel]. */
private class OkHttpRangeFetcher(
    private val client: OkHttpClient,
    private val url: String,
    private val headers: Map<String, String>,
) : RangeFetcher {

    override fun open(start: Long, endInclusive: Long): RangeConnection {
        val (connection, code, _) = execute(start, endInclusive)
        if (code != HTTP_PARTIAL) {
            connection.close()
            throw IOException("range request answered $code")
        }
        return connection
    }

    /**
     * The first chunk, or `null` when the server ignores ranges (`200`, no `Content-Range`). Network
     * errors propagate so ExoPlayer's retry and the stream fallback timer see them.
     */
    fun probe(start: Long, endInclusive: Long): RangeProbe? {
        val (connection, code, contentRange) = execute(start, endInclusive)
        val total = partialContentTotalLength(code, contentRange)
        if (total == null) {
            connection.close()
            return null
        }
        return RangeProbe(connection, total)
    }

    private fun execute(start: Long, endInclusive: Long): Triple<RangeConnection, Int, String?> {
        val request = Request.Builder().url(url).apply {
            headers.forEach { (name, value) -> header(name, value) }
            header("Range", "bytes=$start-$endInclusive")
            header("Accept-Encoding", "identity")
        }.build()
        val call = client.newCall(request)
        val response = call.execute()
        val body = response.body
        val connection = OkHttpRangeConnection(call, body.byteStream())
        return Triple(connection, response.code, response.header("Content-Range"))
    }
}

private class OkHttpRangeConnection(private val call: Call, private val stream: InputStream) : RangeConnection {
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = stream.read(buffer, offset, length)

    override fun cancel() = call.cancel()

    override fun close() {
        try {
            stream.close()
        } catch (e: IOException) {
            // Already broken: nothing left to release.
        }
    }
}

/** Daemon threads shared by every parallel reader; idle threads die after a minute. */
private object RangeExecutor {
    private val counter = AtomicInteger()
    val instance: ExecutorService = Executors.newCachedThreadPool(
        ThreadFactory { task -> Thread(task, "range-reader-${counter.incrementAndGet()}").apply { isDaemon = true } },
    )
}

private const val HTTP_PARTIAL = 206
