package com.seastreams.tv.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.seastreams.tv.core.database.entity.LibrarySortOrder
import com.seastreams.tv.core.database.entity.ProfileEntity
import com.seastreams.tv.core.util.DefaultDispatcher
import com.seastreams.tv.data.catalog.GenreRepository
import com.seastreams.tv.data.library.LibraryKeywordBackfill
import com.seastreams.tv.data.profile.ProfileRepository
import com.seastreams.tv.data.profile.contentForProfile
import com.seastreams.tv.domain.usecase.GetLibraryUseCase
import com.seastreams.tv.domain.usecase.LibraryEntry
import com.seastreams.tv.domain.usecase.LibraryFilter
import com.seastreams.tv.ui.components.NavTab
import com.seastreams.tv.ui.navigation.WhileUiSubscribed
import dagger.Lazy
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** How long focus must rest on a sidebar filter before the grid switches to it (PRD §7). */
const val FILTER_SETTLE_MILLIS = 100L

data class LibraryUiState(
    val profile: ProfileEntity? = null,
    /** The filter the grid shows; the sidebar's focus-following highlight is [LibraryViewModel.sidebarFilter]. */
    val filter: LibraryFilter = LibraryFilter.ALL,
    val sort: LibrarySortOrder = LibrarySortOrder.A_TO_Z,
    /**
     * Selected genre id; `null` = all genres. It stays selected across filters, so it may be one no
     * title under [filter] has: [LibraryView.appliedGenreId] is the genre the grid actually shows.
     */
    val genreId: Int? = null,
    val view: LibraryView = LibraryView(),
    val isLoading: Boolean = true,
    val missingProfileId: Long? = null,
)

/** The grid's filter and genre, one atomic value: a filter switch keeps the genre in the same emission. */
data class LibrarySelection(val filter: LibraryFilter = LibraryFilter.ALL, val genreId: Int? = null)

/**
 * Library page (PRD §7). Each [open] starts on `All` with every genre; the sort order is the
 * profile's saved one, and changing it is persisted on the profile.
 *
 * The selected genre persists across `All`, `Movies` and `TV Shows` ([deriveGenreViews] shows a filter
 * without it unfiltered) and resets when the user leaves the page: [open] on coming back after the
 * screen was disposed (another tab, Search, a profile switch), [onTabSelected] for Settings, which
 * covers the page without disposing it.
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
    private val keywordBackfill: Lazy<LibraryKeywordBackfill>,
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
        // Fallback for the launch-time keyword backfill (`Christmas` genre), e.g. after that run found no TMDB
        // key; a no-op once the profile's run completed. Resolved off main: the backfill's graph is HTTP-backed.
        viewModelScope.launch(deriveDispatcher) { keywordBackfill.get().ensure(id) }
    }

    /** Hover selection: highlights [selected] now and switches the grid once focus rests on it. */
    fun selectFilter(selected: LibraryFilter) {
        sidebar.value = selected
        cancelSettle()
        if (grid.value.filter == selected) return
        settleJob = viewModelScope.launch {
            delay(FILTER_SETTLE_MILLIS)
            grid.update { it.copy(filter = selected) }
        }
    }

    /** Applies a pending filter switch at once (focus leaving the sidebar); `true` if one was pending. */
    fun commitPendingFilter(): Boolean {
        val pending = settleJob?.takeIf { it.isActive } ?: return false
        pending.cancel()
        settleJob = null
        grid.update { it.copy(filter = sidebar.value) }
        return true
    }

    fun selectGenre(selected: Int?) {
        commitPendingFilter()
        grid.update { it.copy(genreId = selected) }
    }

    /**
     * A top-nav pick from the Library page: one that leaves it resets the genre. Settings covers the page
     * without disposing it, so [open] never runs on its return; for other picks this precedes [open].
     */
    fun onTabSelected(selected: NavTab) {
        if (selected != NavTab.LIBRARY) grid.update { it.copy(genreId = null) }
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
            LibrarySnapshot(all, sort, deriveLibraryViews(all, sort, names))
        }
        return combine(snapshots, grid) { snapshot, selection ->
            val view = snapshot.viewsFor(selection.genreId).getValue(selection.filter)
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

    /**
     * One library/sort/genre-name state's views. The last selected genre's views of every filter are
     * kept, so switching filters with a genre selected stays a map lookup. Only read from the sequential
     * `combine` in [stateFor], so the memo needs no locking.
     */
    private class LibrarySnapshot(
        private val all: List<LibraryEntry>,
        private val sort: LibrarySortOrder,
        private val views: Map<LibraryFilter, LibraryView>,
    ) {
        private var genreViews: Pair<Int, Map<LibraryFilter, LibraryView>>? = null

        fun viewsFor(genreId: Int?): Map<LibraryFilter, LibraryView> {
            if (genreId == null) return views
            genreViews?.let { (id, cached) -> if (id == genreId) return cached }
            return deriveGenreViews(all, sort, genreId, views).also { genreViews = genreId to it }
        }
    }
}
