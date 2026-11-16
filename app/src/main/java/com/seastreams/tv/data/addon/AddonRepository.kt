package com.seastreams.tv.data.addon

import com.seastreams.tv.core.database.dao.AddonDao
import com.seastreams.tv.core.database.entity.AddonEntity
import com.seastreams.tv.core.network.InvalidManifestException
import com.seastreams.tv.core.network.StremioAddonClient
import com.seastreams.tv.core.network.StremioAddonUrl
import com.seastreams.tv.data.UserFacingException
import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Installed Stremio addons. Every mutation that touches sort order runs under [orderLock] so that
 * concurrent portal requests (two phones, double taps) cannot interleave and corrupt the order.
 */
@Singleton
class AddonRepository @Inject constructor(
    private val addonDao: AddonDao,
    private val addonClient: StremioAddonClient,
    private val clock: Clock,
) {
    private val orderLock = Mutex()

    fun observeAddons(): Flow<List<AddonEntity>> = addonDao.observeAll()

    /** Seeds [BuiltInAddons] on first launch. Already-present URLs are left untouched. */
    suspend fun seedDefaults() = orderLock.withLock {
        addonDao.insertIfAbsent(BuiltInAddons.defaults(clock.millis()))
    }

    /** Fetches, validates and installs the addon at [rawUrl] at the bottom of the list. */
    suspend fun install(rawUrl: String): AddonEntity {
        val manifestUrl = StremioAddonUrl.normalize(rawUrl)
            ?: throw UserFacingException("That doesn't look like an addon manifest URL")
        if (addonDao.getByUrl(manifestUrl) != null) throw UserFacingException("That addon is already installed")
        val fetched = try {
            addonClient.fetchManifest(manifestUrl)
        } catch (e: InvalidManifestException) {
            throw UserFacingException("Invalid addon manifest: ${e.message}")
        } catch (e: ResponseException) {
            throw UserFacingException("The addon server answered HTTP ${e.response.status.value}")
        } catch (e: IOException) {
            throw UserFacingException("Couldn't reach the addon server")
        }
        return orderLock.withLock {
            if (addonDao.getByUrl(manifestUrl) != null) throw UserFacingException("That addon is already installed")
            val entity = AddonEntity(
                manifestUrl = manifestUrl,
                manifestId = fetched.manifest.id,
                name = fetched.manifest.name,
                customName = null,
                enabled = true,
                sortOrder = addonDao.nextSortOrder(),
                manifestJson = fetched.rawJson,
                installedAtMillis = clock.millis(),
            )
            addonDao.upsert(entity)
            entity
        }
    }

    suspend fun remove(manifestUrl: String) = orderLock.withLock {
        requireExisting(manifestUrl)
        if (BuiltInAddons.isProtected(manifestUrl)) throw UserFacingException("Built-in addons can't be removed")
        addonDao.delete(manifestUrl)
        // Close the gap so sort orders stay dense.
        addonDao.reorder(addonDao.getAll().map { it.manifestUrl })
    }

    /** Sets a display name; a blank [name] restores the manifest's own name. */
    suspend fun rename(manifestUrl: String, name: String) {
        requireExisting(manifestUrl)
        val trimmed = name.trim()
        if (trimmed.length > MAX_NAME_LENGTH) throw UserFacingException("Names can be at most $MAX_NAME_LENGTH characters")
        addonDao.rename(manifestUrl, trimmed.ifEmpty { null })
    }

    suspend fun setEnabled(manifestUrl: String, enabled: Boolean) {
        requireExisting(manifestUrl)
        if (!enabled && BuiltInAddons.isProtected(manifestUrl)) {
            throw UserFacingException("Built-in addons can't be disabled")
        }
        addonDao.setEnabled(manifestUrl, enabled)
    }

    /** Moves one addon up (negative [offset]) or down (positive [offset]). */
    suspend fun move(manifestUrl: String, offset: Int) = orderLock.withLock {
        val current = addonDao.getAll().map { it.manifestUrl }
        if (manifestUrl !in current) throw UserFacingException("Addon not found")
        addonDao.reorder(AddonOrdering.move(current, manifestUrl, offset))
    }

    /** Applies a full client-side order (e.g. after drag-and-drop), reconciled with what exists. */
    suspend fun reorder(requestedOrder: List<String>) = orderLock.withLock {
        val current = addonDao.getAll().map { it.manifestUrl }
        addonDao.reorder(AddonOrdering.reconcile(current, requestedOrder))
    }

    private suspend fun requireExisting(manifestUrl: String): AddonEntity =
        addonDao.getByUrl(manifestUrl) ?: throw UserFacingException("Addon not found")

    companion object {
        const val MAX_NAME_LENGTH = 40
    }
}
