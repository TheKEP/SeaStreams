package com.seastreams.tv.ui.theme

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.seastreams.tv.core.datastore.BackgroundSwatches
import com.seastreams.tv.core.datastore.SettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The active profile's page background (PRD §11 Visual Background Color Picker). [background] is
 * that profile's saved colour, or the swatch being previewed while the picker has focus on it.
 * Saves go to the active profile only (see [SettingsDataStore]).
 */
@HiltViewModel
class AppearanceViewModel @Inject constructor(private val settings: SettingsDataStore) : ViewModel() {

    val saved: StateFlow<Int> = settings.settings
        .map { it.appearance.backgroundArgb }
        .stateIn(viewModelScope, SharingStarted.Eagerly, BackgroundSwatches.DEFAULT)

    private val preview = MutableStateFlow<Int?>(null)

    val background: StateFlow<Int> = combine(saved, preview) { savedColor, previewColor -> previewColor ?: savedColor }
        .stateIn(viewModelScope, SharingStarted.Eagerly, BackgroundSwatches.DEFAULT)

    init {
        // Settings are per profile and this ViewModel is activity-scoped: an unconfirmed preview
        // from one profile must never carry over to the next one.
        viewModelScope.launch {
            settings.activeProfileId.drop(1).collect { preview.value = null }
        }
    }

    /** Focus moved onto a swatch: show it at once without saving. */
    fun preview(argb: Int) {
        preview.value = argb
    }

    /** `Back` (or leaving the swatches): drop the preview and show the saved colour again. */
    fun cancelPreview() {
        preview.value = null
    }

    /** `Enter` on a swatch: persist it. The preview is held until the saved value has caught up. */
    fun confirm(argb: Int) {
        preview.value = argb
        viewModelScope.launch {
            settings.setBackgroundColor(argb)
            preview.compareAndSet(argb, null)
        }
    }
}
