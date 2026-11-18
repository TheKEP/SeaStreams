package com.seastreams.tv.core.webserver

import com.seastreams.tv.core.database.entity.AddonEntity
import com.seastreams.tv.core.datastore.SettingsDataStore
import com.seastreams.tv.core.network.InvalidManifestException
import com.seastreams.tv.core.network.StremioManifestParser
import com.seastreams.tv.core.util.enumByNameOrNull
import com.seastreams.tv.data.UserFacingException
import com.seastreams.tv.data.addon.AddonRepository
import com.seastreams.tv.data.addon.BuiltInAddons
import com.seastreams.tv.data.profile.KidsCeilings
import com.seastreams.tv.data.profile.ProfileDraft
import com.seastreams.tv.data.profile.ProfileRepository
import com.seastreams.tv.data.setup.TmdbKeyRepository
import com.seastreams.tv.domain.model.MovieCertification
import com.seastreams.tv.domain.model.TvCertification
import com.seastreams.tv.domain.usecase.ImportMdbListUseCase
import com.seastreams.tv.domain.usecase.LibraryTarget
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Transport-agnostic portal actions. Routes decode JSON into the request DTOs and call these;
 * every method returns a human-readable confirmation or throws [UserFacingException].
 */
@Singleton
class PortalController @Inject constructor(
    private val tmdbKeys: TmdbKeyRepository,
    private val profiles: ProfileRepository,
    private val addons: AddonRepository,
    private val importMdbList: ImportMdbListUseCase,
    private val settings: SettingsDataStore,
) {

    suspend fun state(): PortalState {
        val profileList = profiles.observeProfiles().first()
        return PortalState(
            tmdbKeySaved = tmdbKeys.hasValidKey.first(),
            tmdbKeyMasked = tmdbKeys.maskedKey.first(),
            maxProfiles = ProfileRepository.MAX_PROFILES,
            avatarCount = ProfileRepository.AVATAR_COUNT,
            kidsMovieCeilings = KidsCeilings.MOVIE.map { it.label },
            kidsTvCeilings = KidsCeilings.TV.map { it.label },
            profiles = profileList.map {
                ProfileDto(it.id, it.name, it.avatarIndex, it.isKids, it.movieCeiling?.label, it.tvCeiling?.label, it.allowUnrated)
            },
            activeProfileId = settings.activeProfileId.first(),
            profileAddons = profileList.map { profile ->
                ProfileAddonsDto(profile.id, addons.observeAddons(profile.id).first().map(::toDto))
            },
        )
    }

    suspend fun saveTmdbKey(request: TmdbKeyRequest): String {
        tmdbKeys.validateAndSave(request.apiKey)
        return "TMDB API key verified and saved"
    }

    suspend fun createProfile(request: ProfileRequest): String {
        val created = profiles.create(request.toDraft())
        return "Created profile “${created.name}”"
    }

    suspend fun updateProfile(profileId: Long, request: ProfileRequest): String {
        val updated = profiles.update(profileId, request.toDraft())
        return "Saved profile “${updated.name}”"
    }

    suspend fun deleteProfile(profileId: Long): String {
        profiles.delete(profileId)
        return "Profile deleted"
    }

    suspend fun installAddon(request: AddonUrlRequest): String {
        val installed = addons.install(request.profileId, request.manifestUrl)
        return "Installed ${installed.name}"
    }

    suspend fun removeAddon(request: AddonUrlRequest): String {
        addons.remove(request.profileId, request.manifestUrl)
        return "Addon removed"
    }

    suspend fun renameAddon(request: AddonRenameRequest): String {
        addons.rename(request.profileId, request.manifestUrl, request.name)
        return "Addon renamed"
    }

    suspend fun setAddonEnabled(request: AddonEnabledRequest): String {
        addons.setEnabled(request.profileId, request.manifestUrl, request.enabled)
        return if (request.enabled) "Addon enabled" else "Addon disabled"
    }

    suspend fun moveAddon(request: AddonMoveRequest): String {
        addons.move(request.profileId, request.manifestUrl, request.offset)
        return "Order updated"
    }

    suspend fun reorderAddons(request: AddonReorderRequest): String {
        addons.reorder(request.profileId, request.manifestUrls)
        return "Order updated"
    }

    suspend fun importToLibrary(request: LibraryImportRequest): String {
        val target = enumByName<LibraryTarget>(request.target, "library section")
        val result = importMdbList(request.profileId, request.url, target)
        return buildString {
            append("Added ${result.added} title${if (result.added == 1) "" else "s"}")
            if (result.alreadyInLibrary > 0) append(", ${result.alreadyInLibrary} already in library")
            if (result.blockedByAgeRestriction > 0) append(", ${result.blockedByAgeRestriction} blocked by age rating")
            if (result.skippedOtherType > 0) append(", ${result.skippedOtherType} of the other type skipped")
            if (result.failed > 0) append(", ${result.failed} couldn't be loaded")
        }
    }

    private fun toDto(entity: AddonEntity): AddonDto {
        val manifest = try {
            StremioManifestParser.parse(entity.manifestJson)
        } catch (e: InvalidManifestException) {
            null
        }
        return AddonDto(
            manifestUrl = entity.manifestUrl,
            name = entity.customName ?: entity.name,
            manifestName = entity.name,
            description = manifest?.description.orEmpty(),
            resources = manifest?.resources?.map { it.name }?.distinct().orEmpty(),
            enabled = entity.enabled,
            builtIn = BuiltInAddons.isProtected(entity.manifestUrl),
        )
    }

    private fun ProfileRequest.toDraft(): ProfileDraft {
        val defaults = ProfileDraft(name = name)
        return defaults.copy(
            avatarIndex = avatarIndex,
            isKids = isKids,
            movieCeiling = movieCeiling?.let {
                MovieCertification.fromLabel(it) ?: throw UserFacingException("Unknown movie rating")
            } ?: defaults.movieCeiling,
            tvCeiling = tvCeiling?.let {
                TvCertification.fromLabel(it) ?: throw UserFacingException("Unknown TV rating")
            } ?: defaults.tvCeiling,
            allowUnrated = allowUnrated,
        )
    }

    private inline fun <reified E : Enum<E>> enumByName(name: String, what: String): E =
        enumByNameOrNull<E>(name) ?: throw UserFacingException("Unknown $what")
}
