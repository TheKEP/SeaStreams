package com.seastreams.tv.ui.screens.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.seastreams.tv.core.database.entity.ProfileEntity
import com.seastreams.tv.core.datastore.SettingsDataStore
import com.seastreams.tv.core.network.ApiEndpoints
import com.seastreams.tv.core.network.TmdbApiService
import com.seastreams.tv.data.profile.ProfileRepository
import com.seastreams.tv.domain.usecase.GetHomeCatalogsUseCase
import com.seastreams.tv.ui.screens.home.HomeImagePrewarmer
import com.seastreams.tv.ui.screens.home.firstScreenImages
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class ProfileSelectUiState(
    val profiles: List<ProfileEntity> = emptyList(),
    val activeProfileId: Long? = null,
    /** Full-bleed artwork on the right; `null` until loaded (or when offline). */
    val backdropUrl: String? = null,
) {
    val canAddProfile: Boolean get() = profiles.size < ProfileRepository.MAX_PROFILES
}

@HiltViewModel
class ProfileSelectViewModel @Inject constructor(
    profiles: ProfileRepository,
    private val settings: SettingsDataStore,
    /** Lazy so the HTTP stack (and its disk cache directory) is built on IO, not during VM creation. */
    private val tmdb: Lazy<TmdbApiService>,
    private val endpoints: ApiEndpoints,
    private val homeCatalogs: GetHomeCatalogsUseCase,
    private val imagePrewarmer: HomeImagePrewarmer,
) : ViewModel() {

    private val backdropUrl = MutableStateFlow<String?>(null)
    private var prefetchJob: Job? = null

    val uiState: StateFlow<ProfileSelectUiState> = combine(
        profiles.observeProfiles(),
        settings.activeProfileId,
        backdropUrl,
    ) { list, active, backdrop ->
        ProfileSelectUiState(list, active, backdrop)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ProfileSelectUiState())

    init {
        loadBackdrop()
    }

    fun selectProfile(profileId: Long) {
        viewModelScope.launch { settings.setActiveProfileId(profileId) }
    }

    /**
     * Called when D-pad focus lands on [profile]'s card (including the initial focus): warms that
     * profile's hero, top Home rows and their first-screen images so Home opens already populated.
     * A short settle delay skips profiles the user merely scrolls past. Loads that already started
     * keep running in the shared cache even when focus moves on.
     */
    fun prefetchHome(profile: ProfileEntity) {
        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch {
            delay(PREFETCH_SETTLE_MILLIS)
            try {
                val prefetch = homeCatalogs.prefetch(profile)
                imagePrewarmer.warm(firstScreenImages(prefetch.hero, prefetch.rows.map { it.items }))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Best effort only: Home loads (and reports errors) on its own when opened.
            }
        }
    }

    /** One popular title's backdrop (a single `/discover` call, HTTP-cached for 6h). */
    private fun loadBackdrop() {
        viewModelScope.launch {
            try {
                val pick = withContext(Dispatchers.IO) { tmdb.get() }.discoverMovies().results
                    .filter { !it.adult && it.backdropPath != null }
                    .take(BACKDROP_CANDIDATES)
                    .randomOrNull() ?: return@launch
                val path = pick.backdropPath ?: return@launch
                backdropUrl.value = "${endpoints.tmdbImageBaseUrl}/$BACKDROP_SIZE$path"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Purely decorative: offline or rate-limited simply leaves the dark background.
                backdropUrl.value = null
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val BACKDROP_CANDIDATES = 10
        const val BACKDROP_SIZE = "w1280"
        const val PREFETCH_SETTLE_MILLIS = 150L
    }
}
