package com.seastreams.tv.ui.screens.settings.streams

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.seastreams.tv.core.datastore.SettingsDataStore
import com.seastreams.tv.core.database.entity.ProfileEntity
import com.seastreams.tv.core.datastore.StreamProcessingStore
import com.seastreams.tv.data.profile.ProfileRepository
import com.seastreams.tv.domain.streams.StreamFilters
import com.seastreams.tv.domain.streams.StreamFormatterChoice
import com.seastreams.tv.domain.streams.StreamProcessingSettings
import com.seastreams.tv.domain.streams.StreamSort
import com.seastreams.tv.ui.navigation.WhileUiSubscribed
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

/**
 * The Settings `UI` → `Stream filters` / `Stream sorting` / `Stream formatter` panels (PRD §13). They show and
 * edit the **active profile's** settings only; with no active profile nothing is written. `Copy stream settings from…`
 * replaces them with another profile's ([copyFrom]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StreamProcessingViewModel @Inject constructor(
    private val settings: SettingsDataStore,
    private val store: StreamProcessingStore,
    profileRepository: ProfileRepository,
) : ViewModel() {

    private val _messages = Channel<String>(Channel.BUFFERED)

    /** Toasts (`Stream settings copied from …`). */
    val messages: Flow<String> = _messages.receiveAsFlow()

    /** Every profile; the `Copy stream settings from…` row shows while there are two or more. */
    val profiles: StateFlow<List<ProfileEntity>> = profileRepository.observeProfiles()
        .stateIn(viewModelScope, WhileUiSubscribed, emptyList())

    val state: StateFlow<StreamProcessingSettings> = settings.activeProfileId
        .flatMapLatest { id -> id?.let(store::settings) ?: flowOf(StreamProcessingSettings()) }
        .stateIn(viewModelScope, WhileUiSubscribed, StreamProcessingSettings())

    fun updateFilters(transform: (StreamFilters) -> StreamFilters) = edit { store.updateFilters(it, transform = transform) }

    fun updateSort(transform: (StreamSort) -> StreamSort) = edit { store.updateSort(it, transform = transform) }

    fun updateFormatter(transform: (StreamFormatterChoice) -> StreamFormatterChoice) = edit { store.updateFormatter(it, transform = transform) }

    fun setFilteringEnabled(enabled: Boolean) = edit { store.setFilteringEnabled(it, enabled) }

    fun setSortingEnabled(enabled: Boolean) = edit { store.setSortingEnabled(it, enabled) }

    fun setFormattingEnabled(enabled: Boolean) = edit { store.setFormattingEnabled(it, enabled) }

    /** Copies [source]'s filters, sort lists and formatter (with their master toggles) onto the active profile. */
    fun copyFrom(source: ProfileEntity) = edit { active ->
        if (source.id == active) return@edit
        store.copyProfile(fromProfileId = source.id, toProfileId = active)
        _messages.send(streamSettingsCopiedMessage(source.name))
    }

    private fun edit(block: suspend (Long) -> Unit) {
        viewModelScope.launch {
            try {
                settings.activeProfileId.first()?.let { block(it) }
            } catch (e: IOException) {
                // Unwritable DataStore: the panel keeps showing the stored value.
            }
        }
    }
}
