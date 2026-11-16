package com.seastreams.tv.ui.screens.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.seastreams.tv.core.database.entity.ProfileEntity
import com.seastreams.tv.core.datastore.SettingsDataStore
import com.seastreams.tv.data.profile.ProfileRepository
import com.seastreams.tv.data.profile.contentForProfile
import com.seastreams.tv.domain.model.CatalogRowState
import com.seastreams.tv.domain.model.MediaType
import com.seastreams.tv.domain.model.StreamingProvider
import com.seastreams.tv.domain.usecase.GetDiscoverCatalogsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DiscoverUiState(
    val profile: ProfileEntity? = null,
    val mediaType: MediaType? = null,
    val rows: List<CatalogRowState> = emptyList(),
    val enabledProviders: List<StreamingProvider> = emptyList(),
    /** Set when the profile was deleted while the tab was open. */
    val missingProfileId: Long? = null,
)

/**
 * One Movies or TV Shows tab (one instance per media type, keyed in the activity store). Rows are
 * age-filtered by the use case. When the tab's rows are already in memory (e.g. warmed in the
 * background after Home loaded) both [initialState] and the first emission for the profile carry
 * them, so the tab never flashes an empty or loading frame; otherwise the first emission has no rows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DiscoverViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val settings: SettingsDataStore,
    private val discover: GetDiscoverCatalogsUseCase,
) : ViewModel() {

    private val request = MutableStateFlow<Pair<Long, MediaType>?>(null)

    val uiState: StateFlow<DiscoverUiState> = request.filterNotNull()
        .flatMapLatest { (id, type) ->
            profiles.contentForProfile(id, missing = { DiscoverUiState(missingProfileId = id) }) { profile ->
                combine(discover.observeRows(profile, type), settings.enabledProviders) { rows, providers ->
                    DiscoverUiState(profile = profile, mediaType = type, rows = rows, enabledProviders = providers)
                }.onStart {
                    // Kids profiles have no such tab (and are never snapshotted): never show them cached rows.
                    val cached = if (profile.isKids) null else cachedState(profile.id, type)?.copy(profile = profile)
                    emit(cached ?: DiscoverUiState(profile = profile, mediaType = type))
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), DiscoverUiState())

    /**
     * What the tab for [profileId] can draw on its very first frame, before [show] has run: the rows
     * already held in memory, or an empty state when there are none. Never suspends.
     */
    fun initialState(profileId: Long, mediaType: MediaType): DiscoverUiState {
        val current = uiState.value
        if (current.profile?.id == profileId && current.mediaType == mediaType) return current
        return cachedState(profileId, mediaType) ?: DiscoverUiState()
    }

    private fun cachedState(profileId: Long, mediaType: MediaType): DiscoverUiState? =
        discover.snapshot(profileId, mediaType)?.let { snapshot ->
            DiscoverUiState(
                profile = snapshot.profile,
                mediaType = mediaType,
                rows = snapshot.rows,
                enabledProviders = snapshot.enabledProviders,
            )
        }

    fun show(profileId: Long, mediaType: MediaType) {
        request.value = profileId to mediaType
    }

    fun toggleProvider(provider: StreamingProvider, enabled: Boolean) {
        viewModelScope.launch { settings.setProviderEnabled(provider, enabled) }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
