package com.seastreams.tv.data.addon

import com.seastreams.tv.core.database.dao.AddonDao
import com.seastreams.tv.core.database.dao.ProfileDao
import com.seastreams.tv.core.database.entity.AddonEntity
import com.seastreams.tv.core.network.InvalidManifestException
import com.seastreams.tv.core.network.StremioAddonClient
import com.seastreams.tv.core.network.StremioAddonUrl
import com.seastreams.tv.data.UserFacingException
import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Installed Stremio addons, one independent list per profile: every operation takes the profile
 * whose list it reads or changes and never touches another profile's addons.
 *
 * Every mutation that touches sort order runs under [orderLock] so that concurrent portal requests
 * (two phones, double taps) cannot interleave and corrupt the order.
 */
@Singleton
class AddonRepository @Inject constructor(
    private val addonDao: AddonDao,
    private val profileDao: ProfileDao,
    private val addonClient: StremioAddonClient,
    private val clock: Clock,
    private val usenet: UsenetAddonState,
    private val debrid: DebridAddonState,
) {
    private val orderLock = Mutex()

    /**
     * Runs [block] holding the list-order lock, so no install, move or built-in upkeep interleaves with it (backup
     * restore rewrites whole lists). [block] must not call back into this repository: the lock isn't re-entrant.
     */
    internal suspend fun <T> withOrderLock(block: suspend () -> T): T = orderLock.withLock { block() }

    /**
     * [profileId]'s list as Manage addons and the portal show it: the Usenet entry only while Usenet is configured, the
     * Debrid entry only while Debrid is listed.
     */
    fun observeAddons(profileId: Long): Flow<List<AddonEntity>> =
        combine(addonDao.observeAll(profileId), usenet.configured, debrid.listed) { addons, usenetOn, debridOn ->
            addons.filter { BuiltInAddons.isListed(it.manifestUrl, usenetOn, debridOn) }
        }

    /** Adds the built-in Debrid entry (at the bottom, enabled) to every profile that doesn't have one yet. */
    suspend fun ensureDebrid() = orderLock.withLock {
        addonDao.profileIdsMissing(BuiltInAddons.DEBRID_URL).forEach { profileId ->
            addonDao.insertIfAbsent(listOf(BuiltInAddons.debrid(profileId, addonDao.nextSortOrder(profileId), clock.millis())))
        }
    }

    /** Adds the built-in Usenet entry (at the bottom, enabled) to every profile that doesn't have one yet. */
    suspend fun ensureUsenet() = orderLock.withLock {
        addonDao.profileIdsMissing(BuiltInAddons.USENET_URL).forEach { profileId ->
            addonDao.insertIfAbsent(listOf(BuiltInAddons.usenet(profileId, addonDao.nextSortOrder(profileId), clock.millis())))
        }
    }

    /** Seeds [BuiltInAddons] for [profileId]. Already-present URLs are left untouched. */
    suspend fun seedDefaults(profileId: Long) = orderLock.withLock {
        addonDao.insertIfAbsent(BuiltInAddons.defaults(profileId, clock.millis()))
    }

    /** Re-adds the protected TMDB provider (at the bottom) to any profile that lacks it. */
    suspend fun ensureBuiltIns() = orderLock.withLock {
        addonDao.profileIdsMissing(BuiltInAddons.TMDB_URL).forEach { profileId ->
            addonDao.insertIfAbsent(listOf(BuiltInAddons.tmdb(profileId, addonDao.nextSortOrder(profileId), clock.millis())))
        }
    }

    /** Fetches, validates and installs the addon at [rawUrl] at the bottom of [profileId]'s list. */
    suspend fun install(profileId: Long, rawUrl: String): AddonEntity {
        requireProfile(profileId)
        val manifestUrl = StremioAddonUrl.normalize(rawUrl)
            ?: throw UserFacingException("That doesn't look like an addon manifest URL")
        if (addonDao.getByUrl(profileId, manifestUrl) != null) throw UserFacingException("That addon is already installed")
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
            requireProfile(profileId)
            if (addonDao.getByUrl(profileId, manifestUrl) != null) throw UserFacingException("That addon is already installed")
            val entity = AddonEntity(
                profileId = profileId,
                manifestUrl = manifestUrl,
                manifestId = fetched.manifest.id,
                name = fetched.manifest.name,
                customName = null,
                enabled = true,
                sortOrder = addonDao.nextSortOrder(profileId),
                manifestJson = fetched.rawJson,
                installedAtMillis = clock.millis(),
            )
            addonDao.upsert(entity)
            entity
        }
    }

    suspend fun remove(profileId: Long, manifestUrl: String) = orderLock.withLock {
        requireExisting(profileId, manifestUrl)
        if (BuiltInAddons.isProtected(manifestUrl)) throw UserFacingException("Built-in addons can't be removed")
        addonDao.delete(profileId, manifestUrl)
        // Close the gap so sort orders stay dense.
        addonDao.reorder(profileId, addonDao.getAll(profileId).map { it.manifestUrl })
    }

    /** Sets a display name; a blank [name] restores the manifest's own name. */
    suspend fun rename(profileId: Long, manifestUrl: String, name: String) {
        requireExisting(profileId, manifestUrl)
        val trimmed = name.trim()
        if (trimmed.length > MAX_NAME_LENGTH) throw UserFacingException("Names can be at most $MAX_NAME_LENGTH characters")
        addonDao.rename(profileId, manifestUrl, trimmed.ifEmpty { null })
    }

    suspend fun setEnabled(profileId: Long, manifestUrl: String, enabled: Boolean) {
        requireExisting(profileId, manifestUrl)
        if (!enabled && !BuiltInAddons.canDisable(manifestUrl)) {
            throw UserFacingException("Built-in addons can't be disabled")
        }
        addonDao.setEnabled(profileId, manifestUrl, enabled)
    }

    /** Moves one addon up (negative [offset]) or down (positive [offset]) within [profileId]'s list. */
    suspend fun move(profileId: Long, manifestUrl: String, offset: Int) = orderLock.withLock {
        requireProfile(profileId)
        val current = addonDao.getAll(profileId).map { it.manifestUrl }
        if (manifestUrl !in current) throw UserFacingException("Addon not found")
        addonDao.reorder(profileId, AddonOrdering.move(current, manifestUrl, offset))
    }

    /** Applies a full client-side order (e.g. after drag-and-drop), reconciled with what exists. */
    suspend fun reorder(profileId: Long, requestedOrder: List<String>) = orderLock.withLock {
        requireProfile(profileId)
        val current = addonDao.getAll(profileId).map { it.manifestUrl }
        addonDao.reorder(profileId, AddonOrdering.reconcile(current, requestedOrder))
    }

    private suspend fun requireProfile(profileId: Long) {
        if (profileDao.getById(profileId) == null) throw UserFacingException("Profile not found")
    }

    private suspend fun requireExisting(profileId: Long, manifestUrl: String): AddonEntity {
        requireProfile(profileId)
        return addonDao.getByUrl(profileId, manifestUrl) ?: throw UserFacingException("Addon not found")
    }

    companion object {
        const val MAX_NAME_LENGTH = 40
    }
}
