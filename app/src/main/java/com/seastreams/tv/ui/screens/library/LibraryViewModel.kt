package com.seastreams.tv.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.seastreams.tv.core.database.entity.LibrarySortOrder
import com.seastreams.tv.core.database.entity.ProfileEntity
import com.seastreams.tv.data.catalog.GenreRepository
import com.seastreams.tv.data.profile.ProfileRepository
import com.seastreams.tv.data.profile.contentForProfile
import com.seastreams.tv.domain.model.Genre
import com.seastreams.tv.domain.usecase.GetLibraryUseCase
import com.seastreams.tv.domain.usecase.LibraryEntry
import com.seastreams.tv.domain.usecase.LibraryFilter
import com.seastreams.tv.domain.usecase.LibraryQuery
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LibraryUiState(
    val profile: ProfileEntity? = null,
    val filter: LibraryFilter = LibraryFilter.ALL,
    val sort: LibrarySortOrder = LibrarySortOrder.A_TO_Z,
    /** Selected genre id; `null` = all genres. */
    val genreId: Int? = null,
    /** Genres present in the filtered library, by name. */
    val genreOptions: List<Genre> = emptyList(),
    /** The grid, filtered and sorted. */
    val entries: List<LibraryEntry> = emptyList(),
    /** First grid index for each quick-jump letter that has titles. */
    val letterIndex: Map<Char, Int> = emptyMap(),
    val isLoading: Boolean = true,
    val missingProfileId: Long? = null,
)

/**
 * Library page (PRD §7). Each [open] starts on `All` with every genre; the sort order is the
 * profile's saved one, and changing it is persisted on the profile.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val library: GetLibraryUseCase,
    private val genres: GenreRepository,
) : ViewModel() {

    private val profileId = MutableStateFlow<Long?>(null)
    private val filter = MutableStateFlow(LibraryFilter.ALL)
    private val genreId = MutableStateFlow<Int?>(null)

    val uiState: StateFlow<LibraryUiState> = profileId.filterNotNull()
        .flatMapLatest { id ->
            profiles.contentForProfile(id, missing = { LibraryUiState(missingProfileId = id, isLoading = false) }) { profile ->
                stateFor(profile)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), LibraryUiState())

    fun open(id: Long) {
        filter.value = LibraryFilter.ALL
        genreId.value = null
        profileId.value = id
    }

    fun selectFilter(selected: LibraryFilter) {
        filter.value = selected
        // A genre that no longer appears under the new filter would leave an empty grid.
        genreId.value = null
    }

    fun selectGenre(selected: Int?) {
        genreId.value = selected
    }

    fun selectSort(sort: LibrarySortOrder) {
        val id = profileId.value ?: return
        viewModelScope.launch { library.setSortOrder(id, sort) }
    }

    private fun stateFor(profile: ProfileEntity): Flow<LibraryUiState> = combine(
        library.observe(profile),
        filter,
        genreId,
        genreNames(),
    ) { all, currentFilter, currentGenre, names ->
        val ofType = all.filter { currentFilter.matches(it.media.mediaType) }
        val ordered = LibraryQuery.apply(all, currentFilter, profile.librarySortOrder, currentGenre)
        LibraryUiState(
            profile = profile,
            filter = currentFilter,
            sort = profile.librarySortOrder,
            genreId = currentGenre,
            genreOptions = ofType.flatMap { it.genreIds }.distinct()
                .mapNotNull { gid -> names[gid]?.let { Genre(gid, it) } }
                .sortedBy { it.name },
            entries = ordered,
            letterIndex = LibraryQuery.letterIndex(ordered),
            isLoading = false,
        )
    }

    /** Names for the genre dropdown; the grid shows straight away and names fill in when loaded. */
    private fun genreNames(): Flow<Map<Int, String>> = flow {
        emit(emptyMap())
        emit(genres.allNames())
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
