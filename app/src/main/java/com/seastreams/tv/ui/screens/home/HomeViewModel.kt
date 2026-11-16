package com.seastreams.tv.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.seastreams.tv.core.database.entity.ProfileEntity
import com.seastreams.tv.core.datastore.SettingsDataStore
import com.seastreams.tv.core.util.orNullUnlessCancelled
import com.seastreams.tv.data.profile.ProfileRepository
import com.seastreams.tv.domain.usecase.GetContinueWatchingUseCase
import com.seastreams.tv.domain.usecase.GetHomeCatalogsUseCase
import com.seastreams.tv.ui.screens.catalog.CatalogWarmupManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Home content for one profile. Everything arriving here has already been age-filtered by the use
 * cases. Whenever the profile changes (switch, or its rating ceiling edited) all content reloads,
 * and the first emission for a new profile carries no content, so one profile's titles can never
 * flash up under another.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val settings: SettingsDataStore,
    private val homeCatalogs: GetHomeCatalogsUseCase,
    private val continueWatching: GetContinueWatchingUseCase,
    private val imagePrewarmer: HomeImagePrewarmer,
    private val catalogWarmup: CatalogWarmupManager,
) : ViewModel() {

    private val profileId = MutableStateFlow<Long?>(null)

    val uiState: StateFlow<HomeUiState> = profileId.filterNotNull()
        .flatMapLatest { id ->
            profiles.observeProfile(id).distinctUntilChanged().flatMapLatest { profile ->
                if (profile == null) flowOf(HomeUiState(missingProfileId = id)) else contentFor(profile)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), HomeUiState())

    /** Called by the screen with the profile chosen on the profile gate. */
    fun showProfile(id: Long) {
        profileId.value = id
    }

    /**
     * Starts loading [id]'s Home and suspends until its first screen (hero, first row and their
     * images) is ready, capped at [REVEAL_TIMEOUT_MILLIS]. Content prefetched on the profile screen
     * is picked up from the shared caches, so this is usually near-instant.
     * @return `false` when the cap cut the wait short.
     */
    suspend fun awaitFirstScreen(id: Long): Boolean {
        showProfile(id)
        return awaitHomeReveal(uiState, id, imagePrewarmer::warm)
    }

    /**
     * Called once Home's first load has finished for [profile]: warms the Movies and TV Shows tabs in
     * the background (delayed, so Home stays uncontested). Kids profiles cancel any running warm-up.
     */
    fun onFirstLoadFinished(profile: ProfileEntity) {
        catalogWarmup.schedule(profile)
    }

    private fun contentFor(profile: ProfileEntity): Flow<HomeUiState> = combine(
        heroFor(profile),
        continueWatching.observe(profile).onStart { emit(emptyList()) },
        homeCatalogs.observeRows(profile),
        settings.settings.map { it.player.autoPlayFromContinueWatching }.distinctUntilChanged(),
    ) { hero, resume, rows, autoPlay ->
        HomeUiState(
            profile = profile,
            hero = hero,
            continueWatching = resume,
            rows = rows,
            autoPlayFromContinueWatching = autoPlay,
        )
    }.onStart { emit(HomeUiState(profile = profile)) }

    private fun heroFor(profile: ProfileEntity): Flow<HeroState> = flow {
        emit(HeroState.Loading)
        val items = orNullUnlessCancelled { homeCatalogs.takeHero(profile) }
        emit(if (items.isNullOrEmpty()) HeroState.Failed else HeroState.Loaded(items))
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
