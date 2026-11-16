package com.seastreams.tv.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.seastreams.tv.core.database.entity.ProfileEntity
import com.seastreams.tv.core.datastore.RecentSearchStore
import com.seastreams.tv.core.util.orNullUnlessCancelled
import com.seastreams.tv.data.profile.ProfileRepository
import com.seastreams.tv.data.profile.contentForProfile
import com.seastreams.tv.domain.model.MediaItem
import com.seastreams.tv.domain.usecase.SearchCatalogUseCase
import com.seastreams.tv.domain.usecase.SearchCategory
import com.seastreams.tv.domain.usecase.SearchResults
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.withIndex
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface GridState {
    val categoryId: String

    data class Loading(override val categoryId: String) : GridState
    data class Loaded(override val categoryId: String, val items: List<MediaItem>) : GridState
    data class Failed(override val categoryId: String) : GridState
}

sealed interface ResultsState {
    data object Loading : ResultsState
    data class Loaded(val results: SearchResults) : ResultsState
    data object Failed : ResultsState
}

data class SearchUiState(
    val profile: ProfileEntity? = null,
    val recentSearches: List<String> = emptyList(),
    val categories: List<SearchCategory> = DEFAULT_CATEGORIES,
    val selectedCategory: SearchCategory = SearchCatalogUseCase.TRENDING_MOVIES,
    val grid: GridState = GridState.Loading(SearchCatalogUseCase.TRENDING_MOVIES.id),
    /** `null` while the query is blank (the category browser is shown instead). */
    val results: ResultsState? = null,
    val missingProfileId: Long? = null,
)

private val DEFAULT_CATEGORIES = listOf(SearchCatalogUseCase.TRENDING_MOVIES, SearchCatalogUseCase.TRENDING_SHOWS)

/**
 * Search page state (PRD §7). Every [open] starts fresh: empty query and `Trending Movies` selected.
 * Typing searches after a short pause; submitting (keyboard Done, a voice result or a chip) also
 * records the query in the profile's recent searches.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val search: SearchCatalogUseCase,
    private val recents: RecentSearchStore,
) : ViewModel() {

    private val profileId = MutableStateFlow<Long?>(null)
    private val _query = MutableStateFlow("")
    private val category = MutableStateFlow(SearchCatalogUseCase.TRENDING_MOVIES)

    /** The text field's contents (kept separate so typing never waits on the state pipeline). */
    val query: StateFlow<String> = _query

    val uiState: StateFlow<SearchUiState> = profileId.filterNotNull()
        .flatMapLatest { id ->
            profiles.contentForProfile(id, missing = { SearchUiState(missingProfileId = id) }) { profile -> stateFor(profile) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), SearchUiState())

    fun open(id: Long) {
        _query.value = ""
        category.value = SearchCatalogUseCase.TRENDING_MOVIES
        profileId.value = id
    }

    fun onQueryChange(text: String) {
        _query.value = text
    }

    /** Runs [text] (or the current query) and remembers it as a recent search. */
    fun submit(text: String = _query.value) {
        _query.value = text
        val id = profileId.value ?: return
        viewModelScope.launch { recents.add(id, text) }
    }

    fun clearQuery() {
        _query.value = ""
    }

    fun clearRecentSearches() {
        val id = profileId.value ?: return
        viewModelScope.launch { recents.clear(id) }
    }

    fun selectCategory(selected: SearchCategory) {
        category.value = selected
    }

    private fun stateFor(profile: ProfileEntity): Flow<SearchUiState> = combine(
        recents.observe(profile.id),
        // Re-read per visit, so genres missed while offline appear next time (successes are cached).
        flow { emit(DEFAULT_CATEGORIES); emit(search.categories()) },
        category,
        gridFor(profile),
        resultsFor(profile),
    ) { recent, all, selected, grid, results ->
        SearchUiState(
            profile = profile,
            recentSearches = recent,
            categories = all,
            selectedCategory = selected,
            grid = grid,
            results = results,
        )
    }

    /**
     * The selected category's posters. After the first category, loads wait [CATEGORY_SETTLE_MILLIS]
     * so scrolling quickly through the sidebar only loads where focus comes to rest.
     */
    private fun gridFor(profile: ProfileEntity): Flow<GridState> = category.withIndex().flatMapLatest { (index, selected) ->
        flow {
            emit(GridState.Loading(selected.id))
            if (index > 0) delay(CATEGORY_SETTLE_MILLIS)
            val items = orNullUnlessCancelled { search.loadCategory(profile, selected) }
            emit(if (items == null) GridState.Failed(selected.id) else GridState.Loaded(selected.id, items))
        }
    }

    /** Search results for the trimmed query, [TYPING_DEBOUNCE_MILLIS] after typing stops. */
    private fun resultsFor(profile: ProfileEntity): Flow<ResultsState?> = _query
        .map { RecentSearchStore.normalize(it) }
        .distinctUntilChanged()
        .flatMapLatest { text ->
            if (text.isEmpty()) {
                flowOf(null)
            } else {
                flow {
                    emit(ResultsState.Loading)
                    delay(TYPING_DEBOUNCE_MILLIS)
                    val found = orNullUnlessCancelled { search.search(profile, text) }
                    emit(if (found == null) ResultsState.Failed else ResultsState.Loaded(found))
                }
            }
        }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val TYPING_DEBOUNCE_MILLIS = 400L
        const val CATEGORY_SETTLE_MILLIS = 150L
    }
}
