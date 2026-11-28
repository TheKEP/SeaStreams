package com.seastreams.tv.ui.screens.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import com.seastreams.tv.core.datastore.SettingsDataStore
import com.seastreams.tv.core.datastore.SubtitleMode
import com.seastreams.tv.ui.components.SeaIcons

/** `Playback` (PRD §11): player, auto-play, failover (+ its indented timeout), buffering, HDR/Dolby Vision. */
internal fun playbackItems(state: SettingsUiState, cb: SettingsCallbacks): List<SettingsItem> {
    val p = state.settings.player
    return buildList {
        add(entry(PLAYER_ROW_KEY, SeaIcons.Television, "Player", playerLabel(p.playerChoice, state.players), RowTrailing.Chevron) { cb.open(SettingsPanel.Choice(ChoiceKind.PLAYER)) })
        add(
            entry("autoplay-top", Icons.Filled.PlayArrow, "Auto-play top stream", autoPlayTopStreamDescription(p.autoPlayTopStream), RowTrailing.Toggle(p.autoPlayTopStream)) {
                cb.updatePlayer { it.copy(autoPlayTopStream = !it.autoPlayTopStream) }
            },
        )
        val preferred = state.addons.firstOrNull { it.manifestUrl == p.preferredAutoPlayAddonUrl }
        add(
            entry("autoplay-addon", Icons.Filled.Star, "Preferred auto-play addon", preferred?.displayName() ?: FIRST_ENABLED_ADDON, RowTrailing.Chevron) {
                cb.open(SettingsPanel.Choice(ChoiceKind.PREFERRED_ADDON))
            },
        )
        add(toggle("autoplay-next", SeaIcons.Episodes, "Auto-play next episode", p.autoPlayNextEpisode) { cb.updatePlayer { it.copy(autoPlayNextEpisode = !it.autoPlayNextEpisode) } })
        add(toggle("next-prompt", SeaIcons.SkipNext, "Next episode prompt", p.nextEpisodePrompt) { cb.updatePlayer { it.copy(nextEpisodePrompt = !it.nextEpisodePrompt) } })
        add(entry(SKIP_INTRO_ROW_KEY, SeaIcons.SkipNext, "Skip intro & recap", p.skipIntroRecap.label, RowTrailing.Chevron) { cb.open(SettingsPanel.Choice(ChoiceKind.SKIP_INTRO_RECAP)) })
        add(entry(SKIP_CREDITS_ROW_KEY, SeaIcons.SkipNext, "Skip credits", p.skipCredits.label, RowTrailing.Chevron) { cb.open(SettingsPanel.Choice(ChoiceKind.SKIP_CREDITS)) })
        add(
            entry("skip-online", SeaIcons.Language, "Online skip times (IntroDB)", onlineSkipDescription(p.onlineSkipTimes), RowTrailing.Toggle(p.onlineSkipTimes)) {
                cb.updatePlayer { it.copy(onlineSkipTimes = !it.onlineSkipTimes) }
            },
        )
        add(
            toggle("autoplay-cw", Icons.Filled.Refresh, "Auto-play from continue watching", p.autoPlayFromContinueWatching) {
                cb.updatePlayer { it.copy(autoPlayFromContinueWatching = !it.autoPlayFromContinueWatching) }
            },
        )
        add(
            toggle("next-up-cw", SeaIcons.Episodes, "Show next up in continue watching", p.showNextUpInContinueWatching) {
                cb.updatePlayer { it.copy(showNextUpInContinueWatching = !it.showNextUpInContinueWatching) }
            },
        )
        add(
            entry(SPEED_ROW_KEY, SeaIcons.Timer, "Default playback speed", p.defaultPlaybackSpeed.label, RowTrailing.Chevron) {
                cb.open(SettingsPanel.Choice(ChoiceKind.PLAYBACK_SPEED))
            },
        )
        add(
            entry(FAILOVER_ROW_KEY, SeaIcons.SkipNext, "Automatic stream failover", failoverDescription(p.autoStreamFailover), RowTrailing.Toggle(p.autoStreamFailover)) {
                cb.updatePlayer { it.copy(autoStreamFailover = !it.autoStreamFailover) }
            },
        )
        // The timeout only applies while failover is on: an indented sub-setting directly beneath it.
        if (p.autoStreamFailover) {
            add(
                entry(
                    FALLBACK_ROW_KEY, SeaIcons.Timer, "Failover timeout duration", "${p.fallbackTimeoutSeconds}s",
                    RowTrailing.Stepper(
                        onDecrease = { cb.updatePlayer { it.copy(fallbackTimeoutSeconds = SettingsDataStore.stepFallbackTimeout(it.fallbackTimeoutSeconds, -1)) } },
                        onIncrease = { cb.updatePlayer { it.copy(fallbackTimeoutSeconds = SettingsDataStore.stepFallbackTimeout(it.fallbackTimeoutSeconds, 1)) } },
                    ),
                    indented = true,
                ) { cb.updatePlayer { it.copy(fallbackTimeoutSeconds = nextFallbackTimeout(it.fallbackTimeoutSeconds)) } },
            )
        }
        add(entry("buffer", SeaIcons.Timer, "Playback buffer profile", p.playbackBufferProfile.label, RowTrailing.Chevron) { cb.open(SettingsPanel.Choice(ChoiceKind.BUFFER_PROFILE)) })
        add(
            entry("parallel", SeaIcons.Addons, "Parallel stream connections", parallelLabel(p.parallelStreamConnections), RowTrailing.Chevron) {
                cb.open(SettingsPanel.Choice(ChoiceKind.PARALLEL_CONNECTIONS))
            },
        )
        add(toggle("dv7", SeaIcons.Brightness, "Convert Dolby Vision Profile 7 to HDR", p.convertDvProfile7ToHdr) { cb.updatePlayer { it.copy(convertDvProfile7ToHdr = !it.convertDvProfile7ToHdr) } })
        add(entry("dolby-vision", SeaIcons.Brightness, "Dolby Vision playback", p.dolbyVisionPlayback.label, RowTrailing.Chevron) { cb.open(SettingsPanel.Choice(ChoiceKind.DOLBY_VISION)) })
        add(toggle(HDR_DIM_ROW_KEY, SeaIcons.Brightness, "HDR white dimming", p.hdrWhiteDimming) { cb.updatePlayer { it.copy(hdrWhiteDimming = !it.hdrWhiteDimming) } })
    }
}

/** `Audio` (PRD §11): passthrough, DSP effects, delay/advance, language. */
internal fun audioItems(state: SettingsUiState, cb: SettingsCallbacks): List<SettingsItem> {
    val a = state.settings.audio
    val vm = cb.vm
    return listOf(
        entry(
            "passthrough", SeaIcons.Volume, "Audio passthrough",
            if (a.passthroughEnabled) "On: Dolby/DTS go to your receiver untouched. Turning on an effect below switches this off" else "Off: Dialogue boost, Night mode, Volume level and Audio delay/advance apply",
            RowTrailing.Toggle(a.passthroughEnabled),
        ) { vm.updateAudio { it.copy(passthroughEnabled = !it.passthroughEnabled) } },
        entry("dialogue", SeaIcons.Equalizer, "Dialogue boost", dialogueLabel(a.dialogueBoost.gainDb), RowTrailing.Chevron) { cb.open(SettingsPanel.Choice(ChoiceKind.DIALOGUE_BOOST)) },
        toggle("night", SeaIcons.Moon, "Night mode", a.nightMode) { vm.updateAudio { it.copy(nightMode = !it.nightMode) } },
        entry(VOLUME_ROW_KEY, SeaIcons.Volume, "Default volume level", a.volumeBoost.label, RowTrailing.Chevron) { cb.open(SettingsPanel.Choice(ChoiceKind.VOLUME_BOOST)) },
        entry(
            "audio-delay", SeaIcons.Timer, "Audio delay/advance", audioDelayLabel(a.audioDelayMs),
            RowTrailing.Stepper(
                onDecrease = { vm.updateAudio { it.copy(audioDelayMs = it.audioDelayMs - AUDIO_STEP_MS) } },
                onIncrease = { vm.updateAudio { it.copy(audioDelayMs = it.audioDelayMs + AUDIO_STEP_MS) } },
            ),
        ) { vm.updateAudio { it.copy(audioDelayMs = 0) } },
        entry("audio-lang", SeaIcons.Language, "Preferred audio language", languageName(a.preferredAudioLanguage), RowTrailing.Chevron) { cb.open(SettingsPanel.Choice(ChoiceKind.AUDIO_LANGUAGE)) },
    )
}

/** `Subtitles` (PRD §11): default mode, language, filtering, SDH, styling, auto-sync. */
internal fun subtitleItems(state: SettingsUiState, cb: SettingsCallbacks): List<SettingsItem> {
    val sub = state.settings.subtitles
    val vm = cb.vm
    val alwaysOn = sub.mode == SubtitleMode.ALWAYS_ON
    return listOf(
        entry("subs-mode", SeaIcons.Subtitles, "Subtitles always on/off", if (alwaysOn) "Always On" else "Always Off", RowTrailing.Toggle(alwaysOn)) {
            vm.updateSubtitles { it.copy(mode = if (it.mode == SubtitleMode.ALWAYS_ON) SubtitleMode.ALWAYS_OFF else SubtitleMode.ALWAYS_ON) }
        },
        entry("subs-lang", SeaIcons.Language, "Preferred subtitle language", languageName(sub.preferredLanguage), RowTrailing.Chevron) { cb.open(SettingsPanel.Choice(ChoiceKind.SUBTITLE_LANGUAGE)) },
        toggle("subs-hide", SeaIcons.HiddenEye, "Hide non-preferred language", sub.hideNonPreferredLanguages) { vm.updateSubtitles { it.copy(hideNonPreferredLanguages = !it.hideNonPreferredLanguages) } },
        toggle("subs-sdh", SeaIcons.Subtitles, "Clean SDH", sub.cleanSdh) { vm.updateSubtitles { it.copy(cleanSdh = !it.cleanSdh) } },
        entry("subs-style", SeaIcons.TextSize, "Subtitle styling", styleSummary(sub.style.fontScale, sub.style.edgeType), RowTrailing.Chevron) { cb.open(SettingsPanel.SubtitleStyle) },
        toggle("subs-autosync", SeaIcons.Subtitles, "Auto-sync external subtitles", sub.autoSyncExternal) { vm.updateSubtitles { it.copy(autoSyncExternal = !it.autoSyncExternal) } },
    )
}

const val PLAYER_ROW_KEY = "player"
const val FAILOVER_ROW_KEY = "failover"
const val FALLBACK_ROW_KEY = "fallback"
const val HDR_DIM_ROW_KEY = "hdr-dim"
const val SPEED_ROW_KEY = "default-speed"
const val VOLUME_ROW_KEY = "volume-boost"
const val SKIP_INTRO_ROW_KEY = "skip-intro"
const val SKIP_CREDITS_ROW_KEY = "skip-credits"

/** IntroDB receives the title's IMDb id and season/episode; off, only the file's own chapters are used. */
internal fun onlineSkipDescription(enabled: Boolean): String =
    if (enabled) "Looks up intro and credits times online; chapters in the file are used too" else "Only chapters inside the file are used"
private const val AUDIO_STEP_MS = 25
