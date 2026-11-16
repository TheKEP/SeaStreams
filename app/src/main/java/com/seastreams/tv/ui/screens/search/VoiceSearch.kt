package com.seastreams.tv.ui.screens.search

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * In-app voice input for Search (PRD §7). Uses [SpeechRecognizer] bound inside this app, never a
 * `RecognizerIntent` activity, so the system-wide assistant UI is never launched.
 */
class VoiceSearchState internal constructor() {
    /** `true` while the recognizer is capturing speech. */
    var listening by mutableStateOf(false)
        internal set

    /** Short status shown under the search bar (permission, no match, …); `null` when idle. */
    var message by mutableStateOf<String?>(null)
        internal set

    internal var toggleAction: () -> Unit = {}

    /** Starts listening, asking for the microphone permission first if needed; stops if listening. */
    fun toggle() = toggleAction()
}

/**
 * @param onPartial live transcription while the user speaks.
 * @param onResult the final transcription; it becomes the search query.
 */
@Composable
fun rememberVoiceSearch(onPartial: (String) -> Unit, onResult: (String) -> Unit): VoiceSearchState {
    val context = LocalContext.current
    val state = remember { VoiceSearchState() }
    val partialCallback by rememberUpdatedState(onPartial)
    val resultCallback by rememberUpdatedState(onResult)
    // Created on the first mic press (the availability check is an IPC we keep off screen entry).
    val holder = remember { RecognizerHolder() }

    val listener = remember(state) {
        recognitionListener(
            state = state,
            onPartial = { partialCallback(it) },
            onResult = { resultCallback(it) },
        )
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) holder.start(context, state, listener) else state.message = PERMISSION_MESSAGE
    }
    DisposableEffect(holder) { onDispose { holder.release() } }

    state.toggleAction = {
        when {
            state.listening -> holder.stop(state)
            hasMicPermission(context) -> holder.start(context, state, listener)
            else -> permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    return state
}

private class RecognizerHolder {
    private var recognizer: SpeechRecognizer? = null

    fun start(context: Context, state: VoiceSearchState, listener: RecognitionListener) {
        val active = recognizer ?: createRecognizer(context, listener)
        if (active == null) {
            state.message = UNAVAILABLE_MESSAGE
            return
        }
        recognizer = active
        state.message = "Listening…"
        state.listening = true
        active.startListening(recognizerIntent(context))
    }

    fun stop(state: VoiceSearchState) {
        recognizer?.stopListening()
        state.listening = false
    }

    fun release() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun createRecognizer(context: Context, listener: RecognitionListener): SpeechRecognizer? {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return null
        return SpeechRecognizer.createSpeechRecognizer(context).apply { setRecognitionListener(listener) }
    }

    private fun recognizerIntent(context: Context) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
    }
}

private fun recognitionListener(
    state: VoiceSearchState,
    onPartial: (String) -> Unit,
    onResult: (String) -> Unit,
) = object : RecognitionListener {
    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    override fun onEndOfSpeech() {
        state.listening = false
    }

    override fun onPartialResults(partialResults: Bundle?) {
        firstTranscript(partialResults)?.let(onPartial)
    }

    override fun onResults(results: Bundle?) {
        state.listening = false
        val text = firstTranscript(results)
        state.message = if (text == null) voiceErrorMessage(SpeechRecognizer.ERROR_NO_MATCH) else null
        text?.let(onResult)
    }

    override fun onError(error: Int) {
        state.listening = false
        state.message = voiceErrorMessage(error)
    }
}

private fun firstTranscript(bundle: Bundle?): String? =
    bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

private fun hasMicPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

/** User-facing text for a [SpeechRecognizer] error code. */
internal fun voiceErrorMessage(error: Int): String = when (error) {
    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that. Press the mic to try again."
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> PERMISSION_MESSAGE
    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
        "Voice search needs a network connection."
    SpeechRecognizer.ERROR_AUDIO -> "Couldn't access the microphone."
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Voice search is busy. Try again in a moment."
    else -> "Voice search stopped. Press the mic to try again."
}

internal const val PERMISSION_MESSAGE = "Allow microphone access to search by voice."
internal const val UNAVAILABLE_MESSAGE = "Voice search isn't available on this device."
