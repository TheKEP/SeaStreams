package com.seastreams.tv.data.usenet.headers

import com.seastreams.tv.core.datastore.UsenetSettingsStore
import com.seastreams.tv.core.network.withoutDiskCache
import com.seastreams.tv.core.util.IoDispatcher
import dagger.Lazy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the Prowlarr / Sonarr / Radarr / SABnzbd / NZBGet / NZBHydra2 header versions current
 * (PRD §12). Each check reads every tool's GitHub `releases/latest` in parallel, sending a generic
 * `SeaStreams` User-Agent and nothing about the user. Only plain versions are kept
 * ([releaseVersion]). A check that reached no tool records nothing, so the last good versions (or
 * the built-in fallbacks) stay in use and the next start tries again.
 */
@Singleton
class HeaderVersionUpdater internal constructor(
    /** Lazy: the shared client owns the HTTP disk cache, which must not be opened on the main thread. */
    private val okHttp: Lazy<OkHttpClient>,
    private val store: UsenetSettingsStore,
    private val io: CoroutineDispatcher,
    private val clock: Clock,
    private val apiBase: String,
) {

    @Inject
    constructor(
        okHttp: Lazy<OkHttpClient>,
        store: UsenetSettingsStore,
        @IoDispatcher io: CoroutineDispatcher,
        clock: Clock,
    ) : this(okHttp, store, io, clock, GITHUB_API)

    private val mutex = Mutex()
    private val _updating = MutableStateFlow(false)

    /** True while a check is running (the `Update header versions` row shows `Updating…`). */
    val updating: StateFlow<Boolean> = _updating.asStateFlow()

    /** Checks only when the last successful check is at least 24 h old. Returns whether a check ran and succeeded. */
    suspend fun checkIfDue(): Boolean {
        val last = store.headers.first().checkedAtMillis
        if (last != null && clock.millis() - last in 0 until CHECK_INTERVAL_MILLIS) return false
        return check()
    }

    /** `Update header versions`: checks now. Returns whether at least one version was fetched. */
    suspend fun updateNow(): Boolean = check()

    private suspend fun check(): Boolean = mutex.withLock {
        _updating.value = true
        try {
            val fetched = withContext(io) {
                val client = okHttp.get().withoutDiskCache()
                coroutineScope {
                    HeaderPreset.UPDATABLE.map { preset -> async { preset to latestVersion(client, preset) } }.awaitAll()
                }
            }.mapNotNull { (preset, version) -> version?.let { preset to it } }.toMap()
            if (fetched.isNotEmpty()) store.saveHeaderVersions(fetched, clock.millis())
            fetched.isNotEmpty()
        } finally {
            _updating.value = false
        }
    }

    /** The latest release version of [preset]'s tool, or `null` when GitHub can't be reached or answers oddly. */
    private fun latestVersion(client: OkHttpClient, preset: HeaderPreset): String? {
        val repo = preset.githubRepo ?: return null
        val request = Request.Builder()
            .url("$apiBase/repos/$repo/releases/latest")
            .header("User-Agent", "SeaStreams")
            .header("Accept", "application/vnd.github+json")
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body.string()
                val tag = Json.parseToJsonElement(body).jsonObject["tag_name"]?.jsonPrimitive?.content
                releaseVersion(tag)
            }
        } catch (e: IOException) {
            null
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            // Not a JSON object, or `tag_name` isn't a primitive.
            null
        }
    }

    companion object {
        const val GITHUB_API = "https://api.github.com"
        const val CHECK_INTERVAL_MILLIS = 24L * 60 * 60 * 1000
    }
}
