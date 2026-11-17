package com.seastreams.tv.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.seastreams.tv.domain.model.MediaRef
import com.seastreams.tv.domain.usecase.ObserveWatchedTitlesUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** The active profile's watched titles, fed to [com.seastreams.tv.ui.components.LocalWatchedTitles]. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class WatchedTitlesViewModel @Inject constructor(
    observeWatched: ObserveWatchedTitlesUseCase,
) : ViewModel() {

    private val profileId = MutableStateFlow<Long?>(null)

    val watched: StateFlow<Set<MediaRef>> = profileId.filterNotNull()
        .flatMapLatest { observeWatched.observe(it) }
        .stateIn(viewModelScope, WhileUiSubscribed, emptySet())

    fun open(id: Long) {
        profileId.value = id
    }
}
