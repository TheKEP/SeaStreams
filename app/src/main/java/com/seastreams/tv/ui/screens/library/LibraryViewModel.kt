package com.seastreams.tv.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.seastreams.tv.core.database.entity.LibrarySortOrder
import com.seastreams.tv.core.database.entity.ProfileEntity
import com.seastreams.tv.core.util.DefaultDispatcher
import com.seastreams.tv.data.catalog.GenreRepository
import com.seastreams.tv.data.profile.ProfileRepository
import com.seastreams.tv.data.profile.contentForProfile
import com.seastreams.tv.domain.usecase.GetLibraryUseCase
import com.seastreams.tv.domain.usecase.LibraryEntry
import com.seastreams.tv.domain.usecase.LibraryFilter
import com.seastreams.tv.ui.navigation.WhileUiSubscribed
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** How long focus must rest on a sidebar filter before the grid switches to it (PRD §7). */
const val FILTER_SETTLE_MILLIS = 100L

data class LibraryUiState(
    val profile: ProfileEntity? = null,
    /** The filter the grid shows; the sidebar's focus-following highlight is [LibraryViewModel.sidebarFilter]. */
    val filter: LibraryFilter = LibraryFilter.ALL,
    val sort: LibrarySortOrder = LibrarySortOrder.A_TO_Z,
    /** Selected genre id; `null` = all genres. */
    val genreId: Int? = null,
    val view: LibraryView = LibraryView(),
    val isLoading: Boolean = true,
    val missingProfileId: Long? = null,
)

/** The grid's filter and genre, switched together so a filter change resets the genre in one emission. */
data class LibrarySelection(val filter: LibraryFilter = LibraryFilter.ALL, val genreId: Int? = null)

/**
 * Library page (PRD §7). Each [open] starts on `All` with every genre; the sort order is the
 * profile's saved one, and changing it is persisted on the profile.
 *
 * Moving focus along the sidebar only flips [sidebarFilter]. The grid follows once focus has rested on
 * a filter for [FILTER_SETTLE_MILLIS] (or at once via [commitPendingFilter]), so filters passed through
 * never compose a grid. All filtering, sorting and A–Z indexing runs on [deriveDispatcher]: every
 * filter's view is built ahead of time, and a switch is a map lookup.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val library: GetLibraryUseCase,
    private val genres: GenreRepository,
    @DefaultDispatcher private val deriveDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val profileId = MutableStateFlow<Long?>(null)
    private val sidebar = MutableStateFlow(LibraryFilter.ALL)
    private val grid = MutableStateFlow(LibrarySelection())
    private var settleJob: Job? = null

    /** The sidebar's selected filter: follows D-pad focus at once, ahead of the grid. */
    val sidebarFilter: StateFlow<LibraryFilter> = sidebar.asStateFlow()

    val uiState: StateFlow<LibraryUiState> = profileId.filterNotNull()
        .flatMapLatest { id ->
            profiles.contentForProfile(id, missing = { LibraryUiState(missingProfileId = id, isLoading = false) }) { profile ->
                stateFor(profile)
            }
        }
        .flowOn(deriveDispatcher)
        .stateIn(viewModelScope, WhileUiSubscribed, LibraryUiState())

    fun open(id: Long) {
        cancelSettle()
        sidebar.value = LibraryFilter.ALL
        grid.value = LibrarySelection()
        profileId.value = id
    }

    /** Hover selection: highlights [selected] now and switches the grid once focus rests on it. */
    fun selectFilter(selected: LibraryFilter) {
        sidebar.value = selected
        cancelSettle()
        // A genre that may not appear under the new filter would leave an empty grid, so it resets.
        val target = LibrarySelection(selected)
        if (grid.value == target) return
        settleJob = viewModelScope.launch {
            delay(FILTER_SETTLE_MILLIS)
            grid.value = target
        }
    }

    /** Applies a pending filter switch at once (focus leaving the sidebar); `true` if one was pending. */
    fun commitPendingFilter(): Boolean {
        val pending = settleJob?.takeIf { it.isActive } ?: return false
        pending.cancel()
        settleJob = null
        grid.value = LibrarySelection(sidebar.value)
        return true
    }

    fun selectGenre(selected: Int?) {
        commitPendingFilter()
        grid.value = grid.value.copy(genreId = selected)
    }

    fun selectSort(sort: LibrarySortOrder) {
        val id = profileId.value ?: return
        viewModelScope.launch { library.setSortOrder(id, sort) }
    }

    private fun cancelSettle() {
        settleJob?.cancel()
        settleJob = null
    }

    private fun stateFor(profile: ProfileEntity): Flow<LibraryUiState> {
        val sort = profile.librarySortOrder
        val snapshots = combine(library.observe(profile), genreNames()) { all, names ->
            LibrarySnapshot(all, deriveLibraryViews(all, sort, names))
        }
        return combine(snapshots, grid) { snapshot, selection ->
            val base = snapshot.views.getValue(selection.filter)
            val view = selection.genreId?.let { genreId ->
                deriveLibraryView(snapshot.all, selection.filter, sort, genreId, base.genreOptions)
            } ?: base
            LibraryUiState(
                profile = profile,
                filter = selection.filter,
                sort = sort,
                genreId = selection.genreId,
                view = view,
                isLoading = false,
            )
        }
    }

    /** Names for the genre dropdown; the grid shows straight away and names fill in when loaded. */
    private fun genreNames(): Flow<Map<Int, String>> = flow {
        emit(emptyMap())
        emit(genres.allNames())
    }

    private class LibrarySnapshot(val all: List<LibraryEntry>, val views: Map<LibraryFilter, LibraryView>)
}
