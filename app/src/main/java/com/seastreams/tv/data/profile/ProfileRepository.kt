package com.seastreams.tv.data.profile

import com.seastreams.tv.core.database.dao.AddonDao
import com.seastreams.tv.core.database.dao.ProfileDao
import com.seastreams.tv.core.database.entity.ProfileEntity
import com.seastreams.tv.core.datastore.SettingsDataStore
import com.seastreams.tv.core.network.TmdbApiKeyProvider
import com.seastreams.tv.data.UserFacingException
import com.seastreams.tv.data.addon.BuiltInAddons
import com.seastreams.tv.data.catalog.DiscoverSnapshotStore
import com.seastreams.tv.data.catalog.LoadedCatalogStore
import com.seastreams.tv.domain.model.MovieCertification
import com.seastreams.tv.domain.model.ProfileAvatar
import com.seastreams.tv.domain.model.TvCertification
import kotlinx.coroutines.flow.Flow
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
    /** The DAO, not `AddonRepository`: profile writes must not pull the addon HTTP client into the graph. */
    private val addonDao: AddonDao,
    private val apiKeyProvider: TmdbApiKeyProvider,
    private val settings: SettingsDataStore,
    private val clock: Clock,
    private val discoverSnapshots: DiscoverSnapshotStore,
    private val loadedCatalogs: LoadedCatalogStore,
) {
    private val writeLock = Mutex()

    /** Runs [block] while no profile can be created, edited or deleted (backup restore checks the cap inside it). */
    internal suspend fun <T> withWriteLock(block: suspend () -> T): T = writeLock.withLock { block() }

    /** Drops [profileId]'s cached Discover rows and loaded catalogs after its profile fields changed outside [update]. */
    internal fun forgetCatalogs(profileId: Long) {
        discoverSnapshots.forget(profileId)
        loadedCatalogs.forget(profileId)
    }

    fun observeProfiles(): Flow<List<ProfileEntity>> = profileDao.observeAll()

    /** Emits `null` once the profile is deleted (e.g. from the Web Portal). */
    fun observeProfile(profileId: Long): Flow<ProfileEntity?> = profileDao.observeById(profileId)

    suspend fun getProfile(profileId: Long): ProfileEntity? = profileDao.getById(profileId)

    /**
     * Creates a profile. Profiles require a saved TMDB key (PRD §2 onboarding gate) and are capped
     * at [MAX_PROFILES]. The new profile gets its own copy of the default addons ([BuiltInAddons]).
     */
    suspend fun create(draft: ProfileDraft): ProfileEntity = writeLock.withLock {
        if (apiKeyProvider.currentApiKey().isNullOrBlank()) {
            throw UserFacingException("Save a valid TMDB API key before creating a profile")
        }
        if (profileDao.count() >= MAX_PROFILES) throw UserFacingException("You can have at most $MAX_PROFILES profiles")
        val now = clock.millis()
        val entity = draft.validated().toEntity(id = 0, createdAtMillis = now)
        val created = entity.copy(id = profileDao.insert(entity))
        addonDao.insertIfAbsent(BuiltInAddons.defaults(created.id, now))
        created
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
        // The foreign key cascades too; explicit so the profile's addon list is gone either way.
        addonDao.deleteAllForProfile(profileId)
        discoverSnapshots.forget(profileId)
        loadedCatalogs.forget(profileId)
        // Drops the profile's General Settings and, if it was active, the active selection.
        settings.clearProfile(profileId)
    }

    private fun ProfileDraft.validated(): ProfileDraft = ProfileValidation.validated(this)

    private fun ProfileDraft.toEntity(id: Long, createdAtMillis: Long) = ProfileValidation.entity(this, id, createdAtMillis)

    companion object {
        const val MAX_PROFILES = 5
        const val MAX_NAME_LENGTH = 20
        val AVATAR_COUNT: Int = ProfileAvatar.entries.size
    }
}

/** The profile-field rules shared by the TV form, the portal and backup restore; failures are [UserFacingException]s. */
internal object ProfileValidation {
    fun validated(draft: ProfileDraft): ProfileDraft = with(draft) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw UserFacingException("Enter a profile name")
        if (trimmed.length > ProfileRepository.MAX_NAME_LENGTH) {
            throw UserFacingException("Names can be at most ${ProfileRepository.MAX_NAME_LENGTH} characters")
        }
        if (avatarIndex !in 0 until ProfileRepository.AVATAR_COUNT) throw UserFacingException("Unknown avatar")
        if (isKids && movieCeiling !in KidsCeilings.MOVIE) {
            throw UserFacingException("Kids movie rating must be one of ${KidsCeilings.MOVIE.joinToString { it.label }}")
        }
        if (isKids && tvCeiling !in KidsCeilings.TV) {
            throw UserFacingException("Kids TV rating must be one of ${KidsCeilings.TV.joinToString { it.label }}")
        }
        copy(name = trimmed)
    }

    /** [draft] as a row: Adult profiles store no ceilings and never allow unrated titles. */
    fun entity(draft: ProfileDraft, id: Long, createdAtMillis: Long) = with(draft) {
        ProfileEntity(
            id = id,
            name = name,
            avatarIndex = avatarIndex,
            isKids = isKids,
            movieCeiling = movieCeiling.takeIf { isKids },
            tvCeiling = tvCeiling.takeIf { isKids },
            allowUnrated = isKids && allowUnrated,
            createdAtMillis = createdAtMillis,
        )
    }
}
