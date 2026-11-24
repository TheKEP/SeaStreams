package com.seastreams.tv.ui.screens.search

import android.app.Activity
import android.speech.SpeechRecognizer

/**
 * Pure voice-search routing for Search (PRD §7), kept free of Android runtime calls so it is
 * JVM-testable. [rememberVoiceSearch] supplies the device facts and performs the side effects.
 */
enum class VoiceBackend {
    /** In-app [SpeechRecognizer] (Google TV / Android TV): never opens a system UI. */
    IN_APP,

    /** The device's `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` activity (no recognition service). */
    SYSTEM_ACTIVITY,

    /** Neither exists (Fire OS gives apps no voice input): the Microphone is hidden. */
    NONE,
}

/**
 * Whether Search shows its Microphone at all: the device has a recognition service or a speech
 * activity (Google TV / Android TV). `false` on Fire OS, where focus starts on the search field.
 */
fun isVoiceInputAvailable(inAppAvailable: Boolean, systemActivityAvailable: Boolean): Boolean =
    chooseVoiceBackend(inAppAvailable, inAppBroken = false, systemActivityAvailable) != VoiceBackend.NONE

/**
 * Picks the backend for a mic press: the in-app recognizer while the device has a recognition
 * service that hasn't failed to bind this session ([inAppBroken]), else the system speech activity.
 */
fun chooseVoiceBackend(inAppAvailable: Boolean, inAppBroken: Boolean, systemActivityAvailable: Boolean): VoiceBackend =
    when {
        inAppAvailable && !inAppBroken -> VoiceBackend.IN_APP
        systemActivityAvailable -> VoiceBackend.SYSTEM_ACTIVITY
        else -> VoiceBackend.NONE
    }

/**
 * Whether a [SpeechRecognizer] error means the recognition service couldn't be reached at all
 * (client / service-disconnected before the recognizer ever became ready), so the system speech
 * activity should take over. Errors after `onReadyForSpeech` are ordinary listening failures.
 */
fun isRecognizerBindFailure(error: Int, readyForSpeech: Boolean): Boolean =
    !readyForSpeech && (error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED)

/** What the system speech activity returned. */
sealed interface SystemVoiceOutcome {
    data class Transcript(val text: String) : SystemVoiceOutcome

    /** Back / dismissed: no error is shown. */
    data object Cancelled : SystemVoiceOutcome

    /** Finished without any usable transcript. */
    data object NoMatch : SystemVoiceOutcome
}

/**
 * Parses an `ACTION_RECOGNIZE_SPEECH` result: `RESULT_OK` yields the top non-blank entry of
 * `RecognizerIntent.EXTRA_RESULTS` ([results]), trimmed; any other code is a cancellation.
 */
fun systemVoiceOutcome(resultCode: Int, results: List<String>?): SystemVoiceOutcome {
    if (resultCode != Activity.RESULT_OK) return SystemVoiceOutcome.Cancelled
    val text = results.orEmpty().firstNotNullOfOrNull { entry -> entry.trim().takeIf { it.isNotEmpty() } }
    return if (text == null) SystemVoiceOutcome.NoMatch else SystemVoiceOutcome.Transcript(text)
}

/** What a voice result does on the Search screen. */
interface VoiceSearchActions {
    /** Sets the query, runs the search and records it as a recent search. */
    fun submit(text: String)

    /** Returns D-pad focus to the Microphone button (after the speech activity closes). */
    fun focusMic()

    /** Focuses the native search field and opens the device keyboard for typing. */
    fun focusAndOpenKeyboard()
}

/** Applies a system speech activity [outcome]; focus always comes back to the Microphone. */
fun applySystemVoiceOutcome(outcome: SystemVoiceOutcome, state: VoiceSearchState, actions: VoiceSearchActions) {
    state.listening = false
    state.message = when (outcome) {
        is SystemVoiceOutcome.Transcript -> null
        SystemVoiceOutcome.Cancelled -> null
        SystemVoiceOutcome.NoMatch -> voiceErrorMessage(SpeechRecognizer.ERROR_NO_MATCH)
    }
    if (outcome is SystemVoiceOutcome.Transcript) actions.submit(outcome.text)
    actions.focusMic()
}

/**
 * A mic press found no working voice path after all (the service failed to bind and there is no
 * speech activity): the Microphone hides for the rest of this Search session, a short note says
 * why ([VoiceSearchState.clearVoiceNotice] removes it on input), and the keyboard opens for typing.
 */
fun applyNoVoiceBackend(state: VoiceSearchState, actions: VoiceSearchActions) {
    state.listening = false
    state.micAvailable = false
    state.message = VOICE_UNAVAILABLE_MESSAGE
    actions.focusAndOpenKeyboard()
}

internal const val VOICE_UNAVAILABLE_MESSAGE = "Voice search isn't available on this device."
