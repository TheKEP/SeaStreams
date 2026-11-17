package com.seastreams.tv.ui.screens.settings

import com.seastreams.tv.core.database.entity.AddonEntity
import com.seastreams.tv.core.datastore.BackgroundSwatches
import com.seastreams.tv.core.datastore.CatalogRowScrollSpeed
import com.seastreams.tv.core.datastore.DialogueBoost
import com.seastreams.tv.core.datastore.PlaybackBufferProfile
import com.seastreams.tv.core.datastore.PlayerSettings
import com.seastreams.tv.core.datastore.PlayerChoice
import com.seastreams.tv.core.network.InvalidManifestException
import com.seastreams.tv.core.network.StremioManifestParser
import com.seastreams.tv.player.external.InstalledPlayer
import java.util.Locale

/** One option of a picker panel. */
data class ChoiceOption(val label: String, val selected: Boolean, val apply: () -> Unit)

data class ChoiceSpec(val title: String, val options: List<ChoiceOption>)

/** Languages offered for audio/subtitle preference (ISO 639-1). */
val PREFERRED_LANGUAGES = listOf(
    "en", "es", "fr", "de", "it", "pt", "nl", "sv", "no", "da", "fi", "pl", "cs", "hu", "ro", "el",
    "ru", "uk", "tr", "ar", "he", "hi", "ja", "ko", "zh",
)

fun languageName(code: String): String =
    Locale.forLanguageTag(code).getDisplayLanguage(Locale.US).ifBlank { code }

fun AddonEntity.displayName(): String = customName ?: name

/** Addons that serve streams (the only ones a preferred auto-play addon can be). */
fun streamAddons(addons: List<AddonEntity>): List<AddonEntity> = addons.filter { addon ->
    addon.enabled && addon.manifestUrl.startsWith("http") && try {
        StremioManifestParser.parse(addon.manifestJson).resources.any { it.name == "stream" }
    } catch (e: InvalidManifestException) {
        false
    }
}

/** Swatch names for screen readers and the row's value (PRD §10: never hex codes). */
fun backgroundName(argb: Int): String = when (BackgroundSwatches.ALL.indexOf(argb)) {
    1 -> "Midnight indigo"
    2 -> "Plum"
    3 -> "Oxblood"
    4 -> "Deep teal"
    5 -> "Navy"
    else -> "Black"
}

/** Picker label: the default speed is marked so it can be found again after changing it. */
fun scrollSpeedOptionLabel(speed: CatalogRowScrollSpeed): String =
    if (speed == CatalogRowScrollSpeed.DEFAULT) "${speed.label} (default)" else speed.label

const val BUILT_IN_PLAYER = "ExoPlayer (built-in)"
const val SYSTEM_CHOOSER = "Other (System chooser)"

fun playerLabel(choice: PlayerChoice, installed: List<InstalledPlayer>): String = when (choice) {
    PlayerChoice.BuiltIn -> BUILT_IN_PLAYER
    PlayerChoice.SystemChooser -> SYSTEM_CHOOSER
    is PlayerChoice.App -> installed.firstOrNull { it.packageName == choice.packageName }?.label ?: "${choice.packageName} (not installed)"
}

/** Options for each picker panel, built from the current [state]. Applying one saves it at once. */
fun choiceSpec(kind: ChoiceKind, state: SettingsUiState, vm: SettingsViewModel): ChoiceSpec {
    val player = state.settings.player
    return when (kind) {
        ChoiceKind.PREFERRED_ADDON -> ChoiceSpec(
            "Preferred auto-play addon",
            listOf(ChoiceOption(FIRST_ENABLED_ADDON, player.preferredAutoPlayAddonUrl == null) { vm.updatePlayer { it.copy(preferredAutoPlayAddonUrl = null) } }) +
                streamAddons(state.addons).map { addon ->
                    ChoiceOption(addon.displayName(), addon.manifestUrl == player.preferredAutoPlayAddonUrl) {
                        vm.updatePlayer { it.copy(preferredAutoPlayAddonUrl = addon.manifestUrl) }
                    }
                },
        )
        ChoiceKind.EXTERNAL_PLAYER -> {
            val choice = player.playerChoice
            val apps = state.players.map { PlayerChoice.App(it.packageName) to it.label }
            // A saved player that has since been uninstalled stays listed so the choice is visible.
            val missing = (choice as? PlayerChoice.App)?.takeIf { saved -> apps.none { it.first == saved } }
            val entries = listOf(PlayerChoice.BuiltIn to BUILT_IN_PLAYER) + apps +
                listOfNotNull(missing?.let { it to "${it.packageName} (not installed)" }) +
                (PlayerChoice.SystemChooser to SYSTEM_CHOOSER)
            ChoiceSpec(
                "External player",
                entries.map { (option, label) -> ChoiceOption(label, option == choice) { vm.updatePlayer { it.copy(playerChoice = option) } } },
            )
        }
        ChoiceKind.DIALOGUE_BOOST -> ChoiceSpec(
            "Dialogue boost",
            DialogueBoost.entries.map { boost ->
                ChoiceOption(dialogueLabel(boost.gainDb), boost == state.settings.audio.dialogueBoost) { vm.updateAudio { it.copy(dialogueBoost = boost) } }
            },
        )
        ChoiceKind.AUDIO_LANGUAGE -> ChoiceSpec(
            "Preferred audio language",
            PREFERRED_LANGUAGES.map { code ->
                ChoiceOption(languageName(code), code == state.settings.audio.preferredAudioLanguage) { vm.updateAudio { it.copy(preferredAudioLanguage = code) } }
            },
        )
        ChoiceKind.CATALOG_ROW_SCROLL_SPEED -> ChoiceSpec(
            "Catalog row scroll speed",
            CatalogRowScrollSpeed.entries.map { speed ->
                ChoiceOption(scrollSpeedOptionLabel(speed), speed == state.settings.appearance.catalogRowScrollSpeed) {
                    vm.setCatalogRowScrollSpeed(speed)
                }
            },
        )
        ChoiceKind.BUFFER_PROFILE -> ChoiceSpec(
            "Playback buffer profile",
            PlaybackBufferProfile.entries.map { profile ->
                val label = if (profile == PlaybackBufferProfile.DEFAULT) "${profile.label} (default)" else profile.label
                ChoiceOption(label, profile == player.playbackBufferProfile) { vm.updatePlayer { it.copy(playbackBufferProfile = profile) } }
            },
        )
        ChoiceKind.PARALLEL_CONNECTIONS -> ChoiceSpec(
            "Parallel stream connections",
            PlayerSettings.PARALLEL_CONNECTIONS_RANGE.map { count ->
                val label = if (count == PlayerSettings.DEFAULT_PARALLEL_CONNECTIONS) "${parallelLabel(count)} (default)" else parallelLabel(count)
                ChoiceOption(label, count == player.parallelStreamConnections) { vm.updatePlayer { it.copy(parallelStreamConnections = count) } }
            },
        )
        ChoiceKind.SUBTITLE_LANGUAGE -> ChoiceSpec(
            "Preferred subtitle language",
            PREFERRED_LANGUAGES.map { code ->
                ChoiceOption(languageName(code), code == state.settings.subtitles.preferredLanguage) { vm.updateSubtitles { it.copy(preferredLanguage = code) } }
            },
        )
    }
}
