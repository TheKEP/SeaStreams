package com.seastreams.tv.ui.screens.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.seastreams.tv.core.database.entity.ProfileEntity
import com.seastreams.tv.core.datastore.SettingsDataStore
import com.seastreams.tv.core.util.orNullUnlessCancelled
import com.seastreams.tv.data.profile.ProfileRepository
import com.seastreams.tv.domain.usecase.GetHomeCatalogsUseCase
import com.seastreams.tv.domain.usecase.GetProfileGateBackdropUseCase
import com.seastreams.tv.ui.screens.home.HomeImagePrewarmer
import com.seastreams.tv.ui.screens.home.firstScreenImages
import com.seastreams.tv.ui.navigation.WhileUiSubscribed
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
    private val profiles: ProfileRepository,
    private val settings: SettingsDataStore,
    /** Lazy so the HTTP stack (and its disk cache directory) is built on IO, not during VM creation. */
    private val gateBackdrop: Lazy<GetProfileGateBackdropUseCase>,
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
    }.stateIn(viewModelScope, WhileUiSubscribed, ProfileSelectUiState())

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
            // Best effort only: Home loads (and reports errors) on its own when opened.
            orNullUnlessCancelled {
                val prefetch = homeCatalogs.prefetch(profile)
                imagePrewarmer.warm(firstScreenImages(prefetch.hero, prefetch.rows.map { it.items }))
            }
        }
    }

    /**
     * A child-safe popular backdrop, filtered by genre and certification in
     * [GetProfileGateBackdropUseCase] (stricter whenever a Kids profile exists).
     */
    private fun loadBackdrop() {
        viewModelScope.launch {
            val anyKids = profiles.observeProfiles().first().any { it.isKids }
            // Purely decorative: offline or rate-limited simply leaves the dark background.
            backdropUrl.value = orNullUnlessCancelled { withContext(Dispatchers.IO) { gateBackdrop.get() }(anyKids) }
        }
    }

    private companion object {
        const val PREFETCH_SETTLE_MILLIS = 150L
    }
}
