package com.seastreams.tv.ui.screens.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.exoplayer.ExoPlayer
import com.seastreams.tv.core.datastore.PlayerSettings
import com.seastreams.tv.core.datastore.SettingsDataStore
import com.seastreams.tv.core.util.MILLIS_PER_SECOND
import com.seastreams.tv.data.details.TitleDetailsRepository
import com.seastreams.tv.domain.model.MediaItem
import com.seastreams.tv.domain.usecase.ResolveTrailerStreamUseCase
import com.seastreams.tv.player.InlineTrailerController
import com.seastreams.tv.player.InlineTrailerPlayerManager
import com.seastreams.tv.player.InlineTrailerSettings
import com.seastreams.tv.player.InlineTrailerSlot
import com.seastreams.tv.player.InlineTrailerState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Activity-wide host of the catalog-row trailer previews (Home, Movies, TV Shows). A title's trailer
 * key is looked up only after the hover delay, one title at a time, so TMDB sees no batch requests.
 */
@HiltViewModel
class InlineTrailerViewModel @Inject constructor(
    settingsStore: SettingsDataStore,
    titles: TitleDetailsRepository,
    resolveTrailer: ResolveTrailerStreamUseCase,
    private val manager: InlineTrailerPlayerManager,
) : ViewModel() {

    private val settings: StateFlow<InlineTrailerSettings> = settingsStore.settings
        .map { it.player.toInlineSettings() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, PlayerSettings().toInlineSettings())

    private val controller = InlineTrailerController(
        scope = viewModelScope,
        settings = { settings.value },
        trailerKeys = { ref -> titles.trailerKeys(ref) },
        resolveStream = { keys -> resolveTrailer(keys, PREVIEW_MAX_HEIGHT) },
        playback = manager,
    )

    val state: StateFlow<InlineTrailerState> = controller.state
    val player: StateFlow<ExoPlayer?> = manager.player

    fun onCardFocused(slot: InlineTrailerSlot, item: MediaItem) = controller.onCardFocused(slot, item)

    fun onFocusLeft(slot: InlineTrailerSlot) = controller.onFocusLeft(slot)

    fun cancel() = controller.cancel()

    /** App backgrounded: stop and free the preview decoder. */
    fun release() {
        controller.cancel()
        manager.release()
    }

    override fun onCleared() = release()

    private companion object {
        /** The card is about 390dp wide: 720p is plenty and keeps previews light. */
        const val PREVIEW_MAX_HEIGHT = 720
    }
}

private fun PlayerSettings.toInlineSettings() = InlineTrailerSettings(
    enabled = autoPlayTrailers,
    muted = muteAutoPlayTrailers,
    delayMs = trailerAutoPlayDelaySeconds * MILLIS_PER_SECOND,
)
