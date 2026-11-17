package com.seastreams.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.seastreams.tv.core.database.dao.AddonDao
import com.seastreams.tv.core.database.entity.AddonEntity
import com.seastreams.tv.core.datastore.AppSettings
import com.seastreams.tv.core.datastore.AudioSettings
import com.seastreams.tv.core.datastore.CatalogRowScrollSpeed
import com.seastreams.tv.core.datastore.PlayerSettings
import com.seastreams.tv.core.datastore.SettingsDataStore
import com.seastreams.tv.core.datastore.SubtitleSettings
import com.seastreams.tv.core.webserver.PortalLink
import com.seastreams.tv.core.webserver.PortalLinkProvider
import com.seastreams.tv.data.UserFacingException
import com.seastreams.tv.data.addon.AddonRepository
import com.seastreams.tv.data.library.LibraryRepository
import com.seastreams.tv.data.library.PendingLibraryClear
import com.seastreams.tv.player.external.InstalledPlayer
import com.seastreams.tv.player.external.InstalledVideoPlayers
import com.seastreams.tv.ui.navigation.WhileUiSubscribed
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val addons: List<AddonEntity> = emptyList(),
    /** Installed apps that can play `video` links, refreshed each time Settings opens. */
    val players: List<InstalledPlayer> = emptyList(),
    val pendingClear: PendingLibraryClear? = null,
    /** Only observed while the Web Portal panel is open. */
    val portal: PortalLink? = null,
)

/** General Settings (PRD §10). Every change is written straight to [SettingsDataStore] / Room. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsStore: SettingsDataStore,
    addonDao: AddonDao,
    /** Lazy: its addon HTTP client is built on IO, not during ViewModel creation. */
    private val addonRepository: Lazy<AddonRepository>,
    private val installedPlayers: InstalledVideoPlayers,
    private val library: LibraryRepository,
    portalLinks: PortalLinkProvider,
) : ViewModel() {

    private val players = MutableStateFlow<List<InstalledPlayer>>(emptyList())
    private val portalVisible = MutableStateFlow(false)
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    val state: StateFlow<SettingsUiState> = combine(
        settingsStore.settings,
        addonDao.observeAll(),
        players,
        library.pendingClear,
        portalVisible.flatMapLatest { visible -> if (visible) portalLinks.observe() else flowOf(null) },
    ) { settings, addons, playerList, pendingClear, portal ->
        SettingsUiState(settings, addons, playerList, pendingClear, portal)
    }.stateIn(viewModelScope, WhileUiSubscribed, SettingsUiState())

    /** Re-scans installed video players (one may have been installed since Settings was last open). */
    fun refreshPlayers() {
        viewModelScope.launch { players.value = installedPlayers.list() }
    }

    fun updatePlayer(transform: (PlayerSettings) -> PlayerSettings) {
        viewModelScope.launch { settingsStore.updatePlayer(transform) }
    }

    fun updateAudio(transform: (AudioSettings) -> AudioSettings) {
        viewModelScope.launch { settingsStore.updateAudio(transform) }
    }

    fun setCatalogRowScrollSpeed(speed: CatalogRowScrollSpeed) {
        viewModelScope.launch { settingsStore.setCatalogRowScrollSpeed(speed) }
    }

    fun updateSubtitles(transform: (SubtitleSettings) -> SubtitleSettings) {
        viewModelScope.launch { settingsStore.updateSubtitles(transform) }
    }

    fun setAddonEnabled(manifestUrl: String, enabled: Boolean) = addonAction { it.setEnabled(manifestUrl, enabled) }

    fun moveAddon(manifestUrl: String, offset: Int) = addonAction { it.move(manifestUrl, offset) }

    fun removeAddon(manifestUrl: String) = addonAction { it.remove(manifestUrl) }

    fun setPortalVisible(visible: Boolean) {
        portalVisible.value = visible
    }

    /** Starts the 5-second Undo window; the library is only emptied once it passes. */
    fun clearLibrary(profileId: Long) {
        viewModelScope.launch { library.scheduleClear(profileId) }
    }

    fun undoClearLibrary() {
        viewModelScope.launch { if (library.undoClear()) _messages.send("Library kept") }
    }

    private fun addonAction(block: suspend (AddonRepository) -> Unit) {
        viewModelScope.launch {
            try {
                block(withContext(Dispatchers.IO) { addonRepository.get() })
            } catch (e: UserFacingException) {
                _messages.send(e.message ?: "That didn't work")
            }
        }
    }
}
