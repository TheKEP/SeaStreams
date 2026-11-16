package com.seastreams.tv.data.profile

import com.seastreams.tv.core.database.dao.ProfileDao
import com.seastreams.tv.core.database.entity.ProfileEntity
import com.seastreams.tv.core.datastore.SettingsDataStore
import com.seastreams.tv.core.network.TmdbApiKeyProvider
import com.seastreams.tv.data.UserFacingException
import com.seastreams.tv.data.catalog.DiscoverSnapshotStore
import com.seastreams.tv.data.catalog.LoadedCatalogStore
import com.seastreams.tv.domain.model.MovieCertification
import com.seastreams.tv.domain.model.ProfileAvatar
import com.seastreams.tv.domain.model.TvCertification
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/** User-editable profile fields, shared by the TV profile form and the Web Portal. */
data class ProfileDraft(
    val name: String,
    val avatarIndex: Int = 0,
    val isKids: Boolean = false,
    val movieCeiling: MovieCertification = DEFAULT_MOVIE_CEILING,
    val tvCeiling: TvCertification = DEFAULT_TV_CEILING,
    val allowUnrated: Boolean = false,
) {
    companion object {
        /** Ceilings pre-selected when a profile is first switched to Kids. */
        val DEFAULT_MOVIE_CEILING = MovieCertification.PG
        val DEFAULT_TV_CEILING = TvCertification.TV_Y7

        fun from(entity: ProfileEntity) = ProfileDraft(
            name = entity.name,
            avatarIndex = entity.avatarIndex,
            isKids = entity.isKids,
            movieCeiling = entity.movieCeiling ?: DEFAULT_MOVIE_CEILING,
            tvCeiling = entity.tvCeiling ?: DEFAULT_TV_CEILING,
            allowUnrated = entity.allowUnrated,
        )
    }
}

/** Kids-profile rating ceilings offered in the UI (PRD §2). */
object KidsCeilings {
    val MOVIE: List<MovieCertification> = listOf(MovieCertification.G, MovieCertification.PG, MovieCertification.PG_13)
    val TV: List<TvCertification> =
        listOf(TvCertification.TV_Y, TvCertification.TV_Y7, TvCertification.TV_G, TvCertification.TV_PG)
}

@Singleton
class ProfileRepository @Inject constructor(
    private val profileDao: ProfileDao,
    private val apiKeyProvider: TmdbApiKeyProvider,
    private val settings: SettingsDataStore,
    private val clock: Clock,
    private val discoverSnapshots: DiscoverSnapshotStore,
    private val loadedCatalogs: LoadedCatalogStore,
) {
    private val writeLock = Mutex()

    fun observeProfiles(): Flow<List<ProfileEntity>> = profileDao.observeAll()

    /** Emits `null` once the profile is deleted (e.g. from the Web Portal). */
    fun observeProfile(profileId: Long): Flow<ProfileEntity?> = profileDao.observeById(profileId)

    suspend fun getProfile(profileId: Long): ProfileEntity? = profileDao.getById(profileId)

    /**
     * Creates a profile. Profiles require a saved TMDB key (PRD §2 onboarding gate) and are capped
     * at [MAX_PROFILES].
     */
    suspend fun create(draft: ProfileDraft): ProfileEntity = writeLock.withLock {
        if (apiKeyProvider.currentApiKey().isNullOrBlank()) {
            throw UserFacingException("Save a valid TMDB API key before creating a profile")
        }
        if (profileDao.count() >= MAX_PROFILES) throw UserFacingException("You can have at most $MAX_PROFILES profiles")
        val entity = draft.validated().toEntity(id = 0, createdAtMillis = clock.millis())
        entity.copy(id = profileDao.insert(entity))
    }

    suspend fun update(profileId: Long, draft: ProfileDraft): ProfileEntity = writeLock.withLock {
        val existing = profileDao.getById(profileId) ?: throw UserFacingException("Profile not found")
        val updated = draft.validated().toEntity(id = existing.id, createdAtMillis = existing.createdAtMillis)
            .copy(librarySortOrder = existing.librarySortOrder)
        profileDao.update(updated)
        discoverSnapshots.forget(profileId)
        loadedCatalogs.forget(profileId)
        updated
    }

    suspend fun delete(profileId: Long) = writeLock.withLock {
        val existing = profileDao.getById(profileId) ?: throw UserFacingException("Profile not found")
        profileDao.delete(existing)
        discoverSnapshots.forget(profileId)
        loadedCatalogs.forget(profileId)
        if (settings.activeProfileId.first() == profileId) settings.setActiveProfileId(null)
    }

    private fun ProfileDraft.validated(): ProfileDraft {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw UserFacingException("Enter a profile name")
        if (trimmed.length > MAX_NAME_LENGTH) throw UserFacingException("Names can be at most $MAX_NAME_LENGTH characters")
        if (avatarIndex !in 0 until AVATAR_COUNT) throw UserFacingException("Unknown avatar")
        if (isKids && movieCeiling !in KidsCeilings.MOVIE) {
            throw UserFacingException("Kids movie rating must be one of ${KidsCeilings.MOVIE.joinToString { it.label }}")
        }
        if (isKids && tvCeiling !in KidsCeilings.TV) {
            throw UserFacingException("Kids TV rating must be one of ${KidsCeilings.TV.joinToString { it.label }}")
        }
        return copy(name = trimmed)
    }

    private fun ProfileDraft.toEntity(id: Long, createdAtMillis: Long) = ProfileEntity(
        id = id,
        name = name,
        avatarIndex = avatarIndex,
        isKids = isKids,
        movieCeiling = movieCeiling.takeIf { isKids },
        tvCeiling = tvCeiling.takeIf { isKids },
        allowUnrated = isKids && allowUnrated,
        createdAtMillis = createdAtMillis,
    )

    companion object {
        const val MAX_PROFILES = 5
        const val MAX_NAME_LENGTH = 20
        val AVATAR_COUNT: Int = ProfileAvatar.entries.size
    }
}
