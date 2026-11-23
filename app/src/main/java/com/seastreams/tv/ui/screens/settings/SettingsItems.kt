package com.seastreams.tv.ui.screens.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.graphics.vector.ImageVector
import com.seastreams.tv.core.datastore.PlayerSettings
import com.seastreams.tv.core.datastore.SettingsDataStore
import com.seastreams.tv.data.usenet.headers.HeaderUse
import com.seastreams.tv.domain.streams.FilterAttribute
import com.seastreams.tv.domain.streams.FilterList
import com.seastreams.tv.domain.streams.StreamProcessingSettings
import com.seastreams.tv.ui.components.SeaIcons
import com.seastreams.tv.ui.screens.settings.sources.DebridUiState
import com.seastreams.tv.ui.screens.settings.sources.SourcesActions
import com.seastreams.tv.ui.screens.settings.sources.SourcesUiState
import com.seastreams.tv.ui.screens.settings.sources.sourcesItems
import com.seastreams.tv.ui.screens.settings.streams.STREAM_FILTERS_ROW_KEY
import com.seastreams.tv.ui.screens.settings.streams.STREAM_FORMATTER_ROW_KEY
import com.seastreams.tv.ui.screens.settings.streams.STREAM_SORT_ROW_KEY
import com.seastreams.tv.ui.screens.settings.streams.filtersSummary
import com.seastreams.tv.ui.screens.settings.streams.sortSummary
import java.time.ZoneId

/** One entry of a Settings category list. */
sealed interface SettingsItem {
    val key: String

    /**
     * A focusable row. [indented] marks a conditional sub-setting drawn beneath its parent toggle
     * (`Mute auto-play trailers`, `Trailer auto-play delay`, `Failover timeout duration`).
     */
    data class Entry(
        override val key: String,
        val icon: ImageVector,
        val title: String,
        val value: String?,
        val trailing: RowTrailing,
        val onClick: () -> Unit,
        val indented: Boolean = false,
    ) : SettingsItem

    /** The background swatch strip, shown under its row while the picker is open. */
    data object Swatches : SettingsItem {
        override val key = "background-swatches"
    }

    /**
     * A non-focusable uppercase subheading (`USENET` in `Sources`); the rows after it, up to the next section, sit in
     * their own card. Focus and the bottom-to-top wrap skip it.
     */
    data class Section(override val key: String, val title: String) : SettingsItem
}

/** Sub-views that replace the category list below the tab bar. */
sealed interface SettingsPanel {
    data object Main : SettingsPanel

    data class Choice(val kind: ChoiceKind) : SettingsPanel

    data object Addons : SettingsPanel

    data object Portal : SettingsPanel

    data object SubtitleStyle : SettingsPanel

    data object UsenetProviders : SettingsPanel

    data object UsenetIndexers : SettingsPanel

    data object UsenetPerformance : SettingsPanel

    data object DebridProviders : SettingsPanel

    /** Search / Download request header picker (PRD §12). */
    data class Headers(val use: HeaderUse) : SettingsPanel

    /** `UI` → stream filtering, sorting and formatter panels (PRD §13). */
    sealed interface Stream : SettingsPanel {
        /** Every filter group, result limits and the deduplicator. */
        data object Filters : Stream

        /** One attribute's Required / Excluded / Included / Preferred lists. */
        data class FilterGroup(val attribute: FilterAttribute) : Stream

        /** One of those lists, as a [MultiChoicePanel][com.seastreams.tv.ui.screens.settings.streams.MultiChoicePanel]. */
        data class FilterValues(val attribute: FilterAttribute, val list: FilterList) : Stream

        data object Sort : Stream

        data object Formatter : Stream
    }
}

/**
 * Where `Back` goes from [this]: a stream filter list returns to its group, a group to `Stream filters`, and every other
 * panel to the category list it was opened from (`UI` for the stream panels), focused on its row.
 */
fun SettingsPanel.parent(): SettingsPanel = when (this) {
    is SettingsPanel.Stream.FilterValues -> SettingsPanel.Stream.FilterGroup(attribute)
    is SettingsPanel.Stream.FilterGroup -> SettingsPanel.Stream.Filters
    else -> SettingsPanel.Main
}

enum class ChoiceKind {
    PREFERRED_ADDON,
    PLAYER,
    DIALOGUE_BOOST,
    AUDIO_LANGUAGE,
    SUBTITLE_LANGUAGE,
    BUFFER_PROFILE,
    PARALLEL_CONNECTIONS,
    DOLBY_VISION,
}

/** Callbacks the lists need; kept apart from the ViewModel so the builders stay plain functions. */
class SettingsCallbacks(
    val open: (SettingsPanel) -> Unit,
    val toggleSwatches: () -> Unit,
    val updatePlayer: ((PlayerSettings) -> PlayerSettings) -> Unit,
    val vm: SettingsViewModel,
    val clearLibrary: () -> Unit,
    val undoClear: () -> Unit,
    val clearCache: () -> Unit,
    val openProfiles: () -> Unit,
    /** `Sources → Update header versions`. */
    val updateHeaders: () -> Unit = {},
    /** `Sources` toggles and scoped clears. */
    val sources: SourcesActions = SourcesActions(),
)

/**
 * The rows of one Settings [category] (PRD §11), in the exact documented order. [clearSecondsLeft]
 * is non-null while a Clear library can still be undone; [cacheBytes] is null while the cache is
 * being measured or cleared.
 */
fun settingsItems(
    category: SettingsCategory,
    state: SettingsUiState,
    swatchesOpen: Boolean,
    clearSecondsLeft: Int?,
    cacheBytes: Long?,
    cb: SettingsCallbacks,
    sources: SourcesUiState = SourcesUiState(),
    debrid: DebridUiState = DebridUiState(),
    streams: StreamProcessingSettings = StreamProcessingSettings(),
    nowMillis: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
): List<SettingsItem> = when (category) {
    SettingsCategory.HOME -> homeItems(state, cb)
    SettingsCategory.UI -> uiItems(state, swatchesOpen, cb) + streamProcessingItems(streams, cb)
    SettingsCategory.PLAYBACK -> playbackItems(state, cb)
    SettingsCategory.AUDIO -> audioItems(state, cb)
    SettingsCategory.SUBTITLES -> subtitleItems(state, cb)
    SettingsCategory.SOURCES -> sourcesItems(sources, cb, nowMillis, zone, debrid)
    SettingsCategory.OTHER -> otherItems(clearSecondsLeft, cacheBytes, cb)
}

private fun homeItems(state: SettingsUiState, cb: SettingsCallbacks): List<SettingsItem> {
    val enabled = state.addons.count { it.enabled }
    return listOf(
        entry(PROFILES_ROW_KEY, Icons.Filled.AccountCircle, "Profiles", "Switch, add or edit profiles", RowTrailing.Chevron, onClick = cb.openProfiles),
        entry(ADDONS_ROW_KEY, SeaIcons.Addons, "Manage addons", "$enabled of ${state.addons.size} enabled", RowTrailing.Chevron) { cb.open(SettingsPanel.Addons) },
        entry(PORTAL_ROW_KEY, SeaIcons.QrCode, "Web portal", "Show the QR code to set things up from your phone", RowTrailing.Chevron) { cb.open(SettingsPanel.Portal) },
    )
}

private fun uiItems(state: SettingsUiState, swatchesOpen: Boolean, cb: SettingsCallbacks): List<SettingsItem> {
    val s = state.settings
    val p = s.player
    return buildList {
        add(entry(BACKGROUND_ROW_KEY, SeaIcons.Palette, "Background color", backgroundName(s.appearance.backgroundArgb), RowTrailing.Chevron, onClick = cb.toggleSwatches))
        if (swatchesOpen) add(SettingsItem.Swatches)
        add(toggle("blur", SeaIcons.HiddenEye, "Blur unwatched episode thumbnails", p.blurUnwatchedThumbnails) { cb.updatePlayer { it.copy(blurUnwatchedThumbnails = !it.blurUnwatchedThumbnails) } })
        add(toggle(TRAILERS_ROW_KEY, SeaIcons.Trailer, "Auto-play trailers", p.autoPlayTrailers) { cb.updatePlayer { it.copy(autoPlayTrailers = !it.autoPlayTrailers) } })
        // Both trailer sub-settings only apply while auto-play is on (PRD §11 indented sub-settings).
        if (p.autoPlayTrailers) {
            add(
                toggle(TRAILERS_MUTE_ROW_KEY, SeaIcons.Volume, "Mute auto-play trailers", p.muteAutoPlayTrailers, indented = true) {
                    cb.updatePlayer { it.copy(muteAutoPlayTrailers = !it.muteAutoPlayTrailers) }
                },
            )
            add(
                entry(
                    TRAILERS_DELAY_ROW_KEY, SeaIcons.Timer, "Trailer auto-play delay", "${p.trailerAutoPlayDelaySeconds}s",
                    RowTrailing.Stepper(
                        onDecrease = { cb.updatePlayer { it.copy(trailerAutoPlayDelaySeconds = SettingsDataStore.clampTrailerDelay(it.trailerAutoPlayDelaySeconds - 1)) } },
                        onIncrease = { cb.updatePlayer { it.copy(trailerAutoPlayDelaySeconds = SettingsDataStore.clampTrailerDelay(it.trailerAutoPlayDelaySeconds + 1)) } },
                    ),
                    indented = true,
                ) { cb.updatePlayer { it.copy(trailerAutoPlayDelaySeconds = nextTrailerDelay(it.trailerAutoPlayDelaySeconds)) } },
            )
        }
    }
}

/**
 * The bottom of `UI` (PRD §13), below the trailer rows: the active profile's stream filters, sorting and formatter.
 * Their panels' `Back` returns to these rows ([SettingsPanel.parent]).
 */
internal fun streamProcessingItems(streams: StreamProcessingSettings, cb: SettingsCallbacks): List<SettingsItem> = listOf(
    entry(STREAM_FILTERS_ROW_KEY, SeaIcons.FilterList, "Stream filters", filtersSummary(streams.filters), RowTrailing.Chevron) {
        cb.open(SettingsPanel.Stream.Filters)
    },
    entry(STREAM_SORT_ROW_KEY, SeaIcons.Sort, "Stream sorting", sortSummary(streams.sort), RowTrailing.Chevron) {
        cb.open(SettingsPanel.Stream.Sort)
    },
    entry(STREAM_FORMATTER_ROW_KEY, Icons.Filled.Edit, "Stream formatter", streams.formatter.id.label, RowTrailing.Chevron) {
        cb.open(SettingsPanel.Stream.Formatter)
    },
)

private fun otherItems(clearSecondsLeft: Int?, cacheBytes: Long?, cb: SettingsCallbacks): List<SettingsItem> = listOf(
    if (clearSecondsLeft != null) {
        entry(CLEAR_ROW_KEY, Icons.Filled.Refresh, "Undo clear library", "Library will be cleared in ${clearSecondsLeft}s", RowTrailing.None, onClick = cb.undoClear)
    } else {
        entry(CLEAR_ROW_KEY, Icons.Filled.Delete, "Clear library", "Removes every title saved to this profile", RowTrailing.None, onClick = cb.clearLibrary)
    },
    entry(CLEAR_CACHE_ROW_KEY, Icons.Filled.Delete, "Clear cache", cacheSizeLabel(cacheBytes), RowTrailing.None, onClick = cb.clearCache),
)

/**
 * For each list index, the row's position among the focusable [SettingsItem.Entry] rows, or `-1` for
 * the swatch strip. Feeds `verticalListEdgeWrap`, so the §1 two-way wrap runs between the first and
 * last *entries* of the category and never lands on the strip.
 */
internal fun settingsEntryOrdinals(items: List<SettingsItem>): IntArray {
    var next = 0
    return IntArray(items.size) { index -> if (items[index] is SettingsItem.Entry) next++ else -1 }
}

internal fun entry(
    key: String,
    icon: ImageVector,
    title: String,
    value: String?,
    trailing: RowTrailing,
    indented: Boolean = false,
    onClick: () -> Unit,
) = SettingsItem.Entry(key, icon, title, value, trailing, onClick, indented)

internal fun toggle(key: String, icon: ImageVector, title: String, checked: Boolean, indented: Boolean = false, onClick: () -> Unit) =
    SettingsItem.Entry(key, icon, title, if (checked) "Enabled" else "Disabled", RowTrailing.Toggle(checked), onClick, indented)

const val PROFILES_ROW_KEY = "profiles"
const val ADDONS_ROW_KEY = "addons"
const val PORTAL_ROW_KEY = "portal"
const val BACKGROUND_ROW_KEY = "background"
const val TRAILERS_ROW_KEY = "trailers"
const val TRAILERS_MUTE_ROW_KEY = "trailers-mute"
const val TRAILERS_DELAY_ROW_KEY = "trailers-delay"
const val CLEAR_ROW_KEY = "clear-library"
const val CLEAR_CACHE_ROW_KEY = "clear-cache"
const val FIRST_ENABLED_ADDON = "First enabled addon"
