package com.seastreams.tv.ui.screens.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.seastreams.tv.core.util.orNullUnlessCancelled
import com.seastreams.tv.data.catalog.LoadedCatalog
import com.seastreams.tv.data.profile.ProfileRepository
import com.seastreams.tv.data.profile.contentForProfile
import com.seastreams.tv.domain.model.CatalogSource
import com.seastreams.tv.domain.model.MediaItem
import com.seastreams.tv.domain.usecase.GetViewMoreUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** What the View More grid is showing. [request] identifies which open it belongs to. */
sealed interface ViewMoreUiState {
    val request: ViewMoreRequest?

    data class Loading(override val request: ViewMoreRequest?) : ViewMoreUiState
    /** [loadingMore]: [items] are the row's titles already in memory and the rest are on their way. */
    data class Loaded(
        override val request: ViewMoreRequest,
        val items: List<MediaItem>,
        val loadingMore: Boolean = false,
    ) : ViewMoreUiState
    data class Failed(override val request: ViewMoreRequest) : ViewMoreUiState

    /** The profile was deleted while the grid was open. */
    data class ProfileMissing(override val request: ViewMoreRequest) : ViewMoreUiState
}

data class ViewMoreRequest(val profileId: Long, val source: CatalogSource)

/**
 * Loads up to 100 age-approved titles of one row's source for the View More grid (PRD §4).
 *
 * The row's titles are already in memory when View More opens: [initialState] hands them to the
 * screen's first frame, and the first emission for the profile repeats them (`loadingMore`) while
 * only the missing titles are fetched; the full list then replaces them with the seed as its prefix,
 * so the grid just grows below the focused poster.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ViewMoreViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val viewMore: GetViewMoreUseCase,
) : ViewModel() {

    private val request = MutableStateFlow<ViewMoreRequest?>(null)

    val uiState: StateFlow<ViewMoreUiState> = request.filterNotNull()
        .flatMapLatest { req ->
            profiles.contentForProfile<ViewMoreUiState>(req.profileId, missing = { ViewMoreUiState.ProfileMissing(req) }) { profile ->
                flow {
                    val seed = viewMore.cached(profile.id, req.source)?.takeIf { it.restriction == profile.toAgeRestriction() }
                    emit(seededState(req, seed))
                    if (seed?.complete == true) return@flow
                    val items = orNullUnlessCancelled { viewMore(profile, req.source, seed) }
                    emit(
                        when {
                            items != null -> ViewMoreUiState.Loaded(req, items)
                            // Keep the row's titles on screen rather than replacing them with an error.
                            seed != null -> ViewMoreUiState.Loaded(req, seed.items)
                            else -> ViewMoreUiState.Failed(req)
                        },
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ViewMoreUiState.Loading(null))

    /**
     * What View More for [profileId] and [source] can draw on its very first frame, before [show] has
     * run: the titles already in memory (the row, or a previously expanded list), else loading.
     * Never suspends.
     */
    fun initialState(profileId: Long, source: CatalogSource): ViewMoreUiState {
        val req = ViewMoreRequest(profileId, source)
        val current = uiState.value
        if (current.request == req) return current
        return seededState(req, viewMore.cached(profileId, source))
    }

    fun show(profileId: Long, source: CatalogSource) {
        request.value = ViewMoreRequest(profileId, source)
    }

    private fun seededState(req: ViewMoreRequest, seed: LoadedCatalog?): ViewMoreUiState =
        if (seed == null) ViewMoreUiState.Loading(req) else ViewMoreUiState.Loaded(req, seed.items, loadingMore = !seed.complete)

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
