package com.seastreams.tv.core.webserver

import com.seastreams.tv.core.util.redacted
import kotlinx.serialization.Serializable

// Request bodies posted by the portal page (JSON). Ratings travel as their labels, e.g. "PG-13".

@Serializable
data class TmdbKeyRequest(val apiKey: String) {
    override fun toString(): String = "TmdbKeyRequest(apiKey=${redacted(apiKey)})"
}

@Serializable
data class ProfileRequest(
    val name: String,
    val avatarIndex: Int = 0,
    val isKids: Boolean = false,
    val movieCeiling: String? = null,
    val tvCeiling: String? = null,
    val allowUnrated: Boolean = false,
)

// Addon requests name the profile whose addon list they change; no other profile is touched.

@Serializable
data class AddonUrlRequest(val profileId: Long, val manifestUrl: String)

@Serializable
data class AddonRenameRequest(val profileId: Long, val manifestUrl: String, val name: String)

@Serializable
data class AddonEnabledRequest(val profileId: Long, val manifestUrl: String, val enabled: Boolean)

@Serializable
data class AddonMoveRequest(val profileId: Long, val manifestUrl: String, val offset: Int)

@Serializable
data class AddonReorderRequest(val profileId: Long, val manifestUrls: List<String>)

/** [target] is `MOVIES` or `TV_SHOWS`. */
@Serializable
data class LibraryImportRequest(val url: String, val profileId: Long, val target: String)

// Responses.

@Serializable
data class ApiMessage(val ok: Boolean, val message: String)

@Serializable
data class ProfileDto(
    val id: Long,
    val name: String,
    val avatarIndex: Int,
    val isKids: Boolean,
    val movieCeiling: String?,
    val tvCeiling: String?,
    val allowUnrated: Boolean,
)

@Serializable
data class AddonDto(
    val manifestUrl: String,
    /** Display name (custom name when set). */
    val name: String,
    val manifestName: String,
    val description: String,
    val resources: List<String>,
    val enabled: Boolean,
    val builtIn: Boolean,
    /** `false` only for TMDB; the built-in `Usenet` entry can be switched off per profile. */
    val canDisable: Boolean = true,
)

/** Everything the portal page renders, fetched in one call after every mutation. */
@Serializable
data class PortalState(
    val tmdbKeySaved: Boolean,
    val tmdbKeyMasked: String?,
    val maxProfiles: Int,
    val avatarCount: Int,
    val kidsMovieCeilings: List<String>,
    val kidsTvCeilings: List<String>,
    val profiles: List<ProfileDto>,
    /** The profile selected on the TV, which the addon editor opens on; `null` when none is active. */
    val activeProfileId: Long?,
    /** Each profile's own addon list, in that profile's order. */
    val profileAddons: List<ProfileAddonsDto>,
)

@Serializable
data class ProfileAddonsDto(val profileId: Long, val addons: List<AddonDto>)
