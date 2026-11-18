package com.seastreams.tv.ui.screens.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.graphics.vector.ImageVector
import com.seastreams.tv.core.datastore.AppSettings
import com.seastreams.tv.core.datastore.PlayerSettings
import com.seastreams.tv.core.datastore.SettingsDataStore
import com.seastreams.tv.core.datastore.SubtitleEdgeType
import com.seastreams.tv.core.datastore.SubtitleMode
import com.seastreams.tv.ui.components.SeaIcons

/** One entry of the main Settings list. */
sealed interface SettingsItem {
    val key: String

    data class Header(override val key: String, val title: String) : SettingsItem

    data class Entry(
        override val key: String,
        val icon: ImageVector,
        val title: String,
        val value: String?,
        val trailing: RowTrailing,
        val onClick: () -> Unit,
    ) : SettingsItem

    /** The background swatch strip, shown under its row while the picker is open. */
    data object Swatches : SettingsItem {
        override val key = "background-swatches"
    }
}

/** Sub-views that replace the main list in the right pane. */
sealed interface SettingsPanel {
    data object Main : SettingsPanel

    data class Choice(val kind: ChoiceKind) : SettingsPanel

    data object Addons : SettingsPanel

    data object Portal : SettingsPanel

    data object SubtitleStyle : SettingsPanel
}

enum class ChoiceKind {
    PREFERRED_ADDON,
    PLAYER,
    DIALOGUE_BOOST,
    AUDIO_LANGUAGE,
    SUBTITLE_LANGUAGE,
    BUFFER_PROFILE,
    PARALLEL_CONNECTIONS,
}

const val BACKGROUND_ROW_KEY = "background"

/** Callbacks the list needs; kept apart from the ViewModel so the builder stays a plain function. */
class SettingsCallbacks(
    val open: (SettingsPanel) -> Unit,
    val toggleSwatches: () -> Unit,
    val updatePlayer: ((PlayerSettings) -> PlayerSettings) -> Unit,
    val vm: SettingsViewModel,
    val clearLibrary: () -> Unit,
    val undoClear: () -> Unit,
)

/**
 * The main list (PRD §10), grouped under `ADDONS & PORTAL`, `UI & APPEARANCE`, `PLAYBACK`, `AUDIO`,
 * `SUBTITLES` and `LIBRARY`. [clearSecondsLeft] is non-null while a Clear Library can still be undone.
 */
fun settingsItems(
    state: SettingsUiState,
    swatchesOpen: Boolean,
    clearSecondsLeft: Int?,
    cb: SettingsCallbacks,
): List<SettingsItem> {
    val s: AppSettings = state.settings
    val p = s.player
    val a = s.audio
    val sub = s.subtitles
    val vm = cb.vm
    return buildList {
        add(SettingsItem.Header("h-addons", "Addons & Portal"))
        val enabled = state.addons.count { it.enabled }
        add(entry("addons", SeaIcons.Addons, "Manage addons", "$enabled of ${state.addons.size} enabled", RowTrailing.Chevron) { cb.open(SettingsPanel.Addons) })
        add(entry("portal", SeaIcons.QrCode, "Web Portal", "Show the QR code to set things up from your phone", RowTrailing.Chevron) { cb.open(SettingsPanel.Portal) })

        add(SettingsItem.Header("h-ui", "UI & Appearance"))
        add(entry(BACKGROUND_ROW_KEY, SeaIcons.Palette, "Background color", backgroundName(s.appearance.backgroundArgb), RowTrailing.Chevron, cb.toggleSwatches))
        if (swatchesOpen) add(SettingsItem.Swatches)
        add(toggle("blur", SeaIcons.HiddenEye, "Blur unwatched episode thumbnails", p.blurUnwatchedThumbnails) { cb.updatePlayer { it.copy(blurUnwatchedThumbnails = !it.blurUnwatchedThumbnails) } })

        add(SettingsItem.Header("h-playback", "Playback"))
        add(entry("autoplay-top", Icons.Filled.PlayArrow, "Auto-play top stream", autoPlayTopStreamDescription(p.autoPlayTopStream), RowTrailing.Toggle(p.autoPlayTopStream)) { cb.updatePlayer { it.copy(autoPlayTopStream = !it.autoPlayTopStream) } })
        val preferred = state.addons.firstOrNull { it.manifestUrl == p.preferredAutoPlayAddonUrl }
        add(entry("autoplay-addon", Icons.Filled.Star, "Preferred auto-play addon", preferred?.displayName() ?: FIRST_ENABLED_ADDON, RowTrailing.Chevron) { cb.open(SettingsPanel.Choice(ChoiceKind.PREFERRED_ADDON)) })
        add(toggle("autoplay-next", SeaIcons.Episodes, "Auto-play next episode", p.autoPlayNextEpisode) { cb.updatePlayer { it.copy(autoPlayNextEpisode = !it.autoPlayNextEpisode) } })
        add(toggle("autoplay-cw", Icons.Filled.Refresh, "Auto-play from Continue Watching", p.autoPlayFromContinueWatching) { cb.updatePlayer { it.copy(autoPlayFromContinueWatching = !it.autoPlayFromContinueWatching) } })
        add(
            entry(
                "fallback", SeaIcons.Timer, "Stream fallback timeout", "${p.fallbackTimeoutSeconds}s",
                RowTrailing.Stepper(
                    onDecrease = { cb.updatePlayer { it.copy(fallbackTimeoutSeconds = SettingsDataStore.clampFallbackTimeout(it.fallbackTimeoutSeconds - 1)) } },
                    onIncrease = { cb.updatePlayer { it.copy(fallbackTimeoutSeconds = SettingsDataStore.clampFallbackTimeout(it.fallbackTimeoutSeconds + 1)) } },
                ),
            ) { cb.updatePlayer { it.copy(fallbackTimeoutSeconds = nextFallbackTimeout(it.fallbackTimeoutSeconds)) } },
        )
        add(entry("buffer", SeaIcons.Timer, "Playback buffer profile", p.playbackBufferProfile.label, RowTrailing.Chevron) { cb.open(SettingsPanel.Choice(ChoiceKind.BUFFER_PROFILE)) })
        add(entry("parallel", SeaIcons.Addons, "Parallel stream connections", parallelLabel(p.parallelStreamConnections), RowTrailing.Chevron) { cb.open(SettingsPanel.Choice(ChoiceKind.PARALLEL_CONNECTIONS)) })
        add(toggle("trailers", SeaIcons.Trailer, "Auto-play trailers", p.autoPlayTrailers) { cb.updatePlayer { it.copy(autoPlayTrailers = !it.autoPlayTrailers) } })
        if (p.autoPlayTrailers) {
            add(toggle("trailers-mute", SeaIcons.Volume, "Mute auto-play trailers", p.muteAutoPlayTrailers) { cb.updatePlayer { it.copy(muteAutoPlayTrailers = !it.muteAutoPlayTrailers) } })
            add(
                entry(
                    "trailers-delay", SeaIcons.Timer, "Trailer auto-play delay", "${p.trailerAutoPlayDelaySeconds}s",
                    RowTrailing.Stepper(
                        onDecrease = { cb.updatePlayer { it.copy(trailerAutoPlayDelaySeconds = SettingsDataStore.clampTrailerDelay(it.trailerAutoPlayDelaySeconds - 1)) } },
                        onIncrease = { cb.updatePlayer { it.copy(trailerAutoPlayDelaySeconds = SettingsDataStore.clampTrailerDelay(it.trailerAutoPlayDelaySeconds + 1)) } },
                    ),
                ) { cb.updatePlayer { it.copy(trailerAutoPlayDelaySeconds = nextTrailerDelay(it.trailerAutoPlayDelaySeconds)) } },
            )
        }
        add(entry("player", SeaIcons.Television, "Player", playerLabel(p.playerChoice, state.players), RowTrailing.Chevron) { cb.open(SettingsPanel.Choice(ChoiceKind.PLAYER)) })
        add(toggle("dv7", SeaIcons.Brightness, "Convert Dolby Vision Profile 7 to HDR", p.convertDvProfile7ToHdr) { cb.updatePlayer { it.copy(convertDvProfile7ToHdr = !it.convertDvProfile7ToHdr) } })
        add(toggle("hdr-dim", SeaIcons.Brightness, "HDR white dimming", p.hdrWhiteDimming) { cb.updatePlayer { it.copy(hdrWhiteDimming = !it.hdrWhiteDimming) } })

        add(SettingsItem.Header("h-audio", "Audio"))
        add(
            entry(
                "passthrough", SeaIcons.Volume, "Audio passthrough",
                if (a.passthroughEnabled) "On: audio goes to your receiver untouched" else "Off: Dialogue Boost, Night Mode and Audio Delay apply",
                RowTrailing.Toggle(a.passthroughEnabled),
            ) { vm.updateAudio { it.copy(passthroughEnabled = !it.passthroughEnabled) } },
        )
        add(entry("dialogue", SeaIcons.Equalizer, "Dialogue boost", dialogueLabel(a.dialogueBoost.gainDb), RowTrailing.Chevron) { cb.open(SettingsPanel.Choice(ChoiceKind.DIALOGUE_BOOST)) })
        add(toggle("night", SeaIcons.Moon, "Night mode", a.nightMode) { vm.updateAudio { it.copy(nightMode = !it.nightMode) } })
        add(
            entry(
                "audio-delay", SeaIcons.Timer, "Audio delay", audioDelayLabel(a.audioDelayMs),
                RowTrailing.Stepper(
                    onDecrease = { vm.updateAudio { it.copy(audioDelayMs = it.audioDelayMs - AUDIO_STEP) } },
                    onIncrease = { vm.updateAudio { it.copy(audioDelayMs = it.audioDelayMs + AUDIO_STEP) } },
                ),
            ) { vm.updateAudio { it.copy(audioDelayMs = 0) } },
        )
        add(entry("audio-lang", SeaIcons.Language, "Preferred audio language", languageName(a.preferredAudioLanguage), RowTrailing.Chevron) { cb.open(SettingsPanel.Choice(ChoiceKind.AUDIO_LANGUAGE)) })

        add(SettingsItem.Header("h-subs", "Subtitles"))
        val alwaysOn = sub.mode == SubtitleMode.ALWAYS_ON
        add(
            entry("subs-mode", SeaIcons.Subtitles, "Subtitles always on", if (alwaysOn) "Always on (when available)" else "Always off", RowTrailing.Toggle(alwaysOn)) {
                vm.updateSubtitles { it.copy(mode = if (it.mode == SubtitleMode.ALWAYS_ON) SubtitleMode.ALWAYS_OFF else SubtitleMode.ALWAYS_ON) }
            },
        )
        add(entry("subs-lang", SeaIcons.Language, "Preferred subtitle language", languageName(sub.preferredLanguage), RowTrailing.Chevron) { cb.open(SettingsPanel.Choice(ChoiceKind.SUBTITLE_LANGUAGE)) })
        add(toggle("subs-hide", SeaIcons.HiddenEye, "Hide non-preferred languages", sub.hideNonPreferredLanguages) { vm.updateSubtitles { it.copy(hideNonPreferredLanguages = !it.hideNonPreferredLanguages) } })
        add(toggle("subs-sdh", SeaIcons.Subtitles, "Clean SDH", sub.cleanSdh) { vm.updateSubtitles { it.copy(cleanSdh = !it.cleanSdh) } })
        add(entry("subs-style", SeaIcons.TextSize, "Subtitle styling", styleSummary(sub.style.fontScale, sub.style.edgeType), RowTrailing.Chevron) { cb.open(SettingsPanel.SubtitleStyle) })

        add(SettingsItem.Header("h-library", "Library"))
        if (clearSecondsLeft != null) {
            add(entry(CLEAR_ROW_KEY, Icons.Filled.Refresh, "Undo clear library", "Library will be cleared in ${clearSecondsLeft}s", RowTrailing.None, cb.undoClear))
        } else {
            add(entry(CLEAR_ROW_KEY, Icons.Filled.Delete, "Clear library", "Removes every title saved to this profile", RowTrailing.None, cb.clearLibrary))
        }
    }
}

const val CLEAR_ROW_KEY = "clear-library"
const val FIRST_ENABLED_ADDON = "First enabled addon"
private const val AUDIO_STEP = 25

private fun entry(key: String, icon: ImageVector, title: String, value: String?, trailing: RowTrailing, onClick: () -> Unit) =
    SettingsItem.Entry(key, icon, title, value, trailing, onClick)

private fun toggle(key: String, icon: ImageVector, title: String, checked: Boolean, onClick: () -> Unit) =
    SettingsItem.Entry(key, icon, title, if (checked) "Enabled" else "Disabled", RowTrailing.Toggle(checked), onClick)

/** Click on the timeout row: next second, wrapping 10s → 1s. */
internal fun nextFallbackTimeout(seconds: Int): Int {
    val range = PlayerSettings.FALLBACK_TIMEOUT_RANGE
    return if (seconds >= range.last) range.first else seconds + 1
}

/** Click on the trailer delay row: next second, wrapping 7s → 1s. */
internal fun nextTrailerDelay(seconds: Int): Int {
    val range = PlayerSettings.TRAILER_DELAY_RANGE
    return if (seconds >= range.last) range.first else seconds + 1
}

/** `Off (1x)`, `2x` … `4x` (PRD §10). */
internal fun parallelLabel(connections: Int): String = if (connections <= 1) "Off (1x)" else "${connections}x"

internal fun dialogueLabel(gainDb: Int): String = if (gainDb == 0) "Off" else "+$gainDb dB"

internal fun audioDelayLabel(delayMs: Int): String = when {
    delayMs > 0 -> "+$delayMs ms"
    delayMs < 0 -> "$delayMs ms"
    else -> "0 ms"
}

private fun styleSummary(fontScale: Float, edge: SubtitleEdgeType): String =
    "${(fontScale * 100).toInt()}% · ${edgeLabel(edge)}"

internal fun edgeLabel(edge: SubtitleEdgeType): String = when (edge) {
    SubtitleEdgeType.NONE -> "No edge"
    SubtitleEdgeType.OUTLINE -> "Outline"
    SubtitleEdgeType.DROP_SHADOW -> "Drop shadow"
}
