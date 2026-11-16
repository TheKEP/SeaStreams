package com.seastreams.tv.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.seastreams.tv.data.profile.ProfileRepository
import com.seastreams.tv.data.setup.TmdbKeyRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Top-level routing decision, derived reactively from Room (profiles) and DataStore (TMDB key). */
enum class AppGate {
    Loading,

    /** No profile yet, or no valid TMDB key: the onboarding [ProfileCreateScreen] is shown. */
    NeedsOnboarding,
    Ready,
}

@HiltViewModel
class AppGateViewModel @Inject constructor(
    profiles: ProfileRepository,
    tmdbKeys: TmdbKeyRepository,
) : ViewModel() {

    val gate: StateFlow<AppGate> = combine(profiles.observeProfiles(), tmdbKeys.hasValidKey) { list, hasKey ->
        resolveGate(profileCount = list.size, hasValidKey = hasKey)
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, AppGate.Loading)

    companion object {
        fun resolveGate(profileCount: Int, hasValidKey: Boolean): AppGate =
            if (profileCount > 0 && hasValidKey) AppGate.Ready else AppGate.NeedsOnboarding
    }
}
