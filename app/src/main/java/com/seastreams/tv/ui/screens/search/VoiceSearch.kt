package com.seastreams.tv.ui.screens.search

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
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
 * Voice input for Search (PRD §7), on two paths chosen per press by [chooseVoiceBackend]:
 * - the in-app [SpeechRecognizer] whenever the device has a recognition service (Google TV /
 *   Android TV), so no system assistant UI is launched;
 * - otherwise (Fire OS), or once that service failed to bind ([isRecognizerBindFailure]), the
 *   device's own `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` activity.
 * Both paths end in [VoiceSearchActions.submit]. With neither ([isVoiceInputAvailable] false: Fire
 * OS gives apps no recognition service and no speech activity) the Microphone is not shown at all.
 */
class VoiceSearchState internal constructor() {
    /** Whether the Microphone is shown; probed once per process, cleared by [applyNoVoiceBackend]. */
    var micAvailable by mutableStateOf(true)
        internal set

    /** `true` while the in-app recognizer is capturing speech. */
    var listening by mutableStateOf(false)
        internal set

    /** Short status shown under the search bar (permission, no match, …); `null` when idle. */
    var message by mutableStateOf<String?>(null)
        internal set

    internal var toggleAction: () -> Unit = {}

    /** Starts voice input (asking for the microphone permission first if needed); stops if listening. */
    fun toggle() = toggleAction()

    /** Removes the "voice unavailable" note once the user types or searches; other messages stay. */
    fun clearVoiceNotice() {
        if (message == VOICE_UNAVAILABLE_MESSAGE) message = null
    }
}

/** The device's voice capability can't change while the app runs, so it is probed once per process. */
private object VoiceCapability {
    @Volatile
    private var available: Boolean? = null

    fun available(context: Context): Boolean {
        available?.let { return it }
        val inApp = SpeechRecognizer.isRecognitionAvailable(context)
        // The activity query only runs without a recognition service, so Google TV does one query.
        val activity = !inApp && hasSystemSpeechActivity(context)
        return isVoiceInputAvailable(inApp, activity).also { available = it }
    }
}

/**
 * @param onPartial live in-app transcription while the user speaks.
 * @param actions what a final transcript (from either path) and the focus fallbacks do.
 */
@Composable
fun rememberVoiceSearch(onPartial: (String) -> Unit, actions: VoiceSearchActions): VoiceSearchState {
    val context = LocalContext.current
    // Decides before the first frame whether the Microphone is shown and where focus starts.
    val state = remember { VoiceSearchState().apply { micAvailable = VoiceCapability.available(context) } }
    val partialCallback by rememberUpdatedState(onPartial)
    val currentActions by rememberUpdatedState(actions)
    // The recognizer itself is created on the first mic press, never on screen entry.
    val holder = remember { RecognizerHolder() }

    val systemSpeech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val results = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        applySystemVoiceOutcome(systemVoiceOutcome(result.resultCode, results), state, currentActions)
    }
    val launchSystem by rememberUpdatedState {
        if (!(hasSystemSpeechActivity(context) && launchSystemSpeech(systemSpeech))) {
            applyNoVoiceBackend(state, currentActions)
        }
    }
    val listener = remember(state, holder) {
        recognitionListener(
            state = state,
            holder = holder,
            onPartial = { partialCallback(it) },
            onResult = { currentActions.submit(it) },
            onBindFailure = { launchSystem() },
        )
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) holder.start(context, state, listener, onUnavailable = launchSystem) else state.message = PERMISSION_MESSAGE
    }
    DisposableEffect(holder) { onDispose { holder.release() } }

    state.toggleAction = toggle@{
        if (state.listening) return@toggle holder.stop(state)
        val inApp = holder.inAppAvailable(context)
        val backend = chooseVoiceBackend(
            inAppAvailable = inApp,
            inAppBroken = holder.inAppBroken,
            // Only probed when the in-app path is out, so Google TV presses skip this query.
            systemActivityAvailable = (!inApp || holder.inAppBroken) && hasSystemSpeechActivity(context),
        )
        when (backend) {
            VoiceBackend.IN_APP ->
                if (hasMicPermission(context)) {
                    holder.start(context, state, listener, onUnavailable = launchSystem)
                } else {
                    permission.launch(Manifest.permission.RECORD_AUDIO)
                }
            VoiceBackend.SYSTEM_ACTIVITY -> launchSystem()
            VoiceBackend.NONE -> applyNoVoiceBackend(state, currentActions)
        }
    }
    return state
}

private class RecognizerHolder {
    private var recognizer: SpeechRecognizer? = null
    private var available: Boolean? = null

    /** Set once the in-app service failed to bind; later presses go straight to the system activity. */
    var inAppBroken = false
        private set

    /** Whether the current in-app session reached `onReadyForSpeech`. */
    var readyForSpeech = false

    /** Whether the user stopped the current session (its trailing errors never trigger a fallback). */
    var userStopped = false
        private set

    fun inAppAvailable(context: Context): Boolean =
        available ?: SpeechRecognizer.isRecognitionAvailable(context).also { available = it }

    fun start(context: Context, state: VoiceSearchState, listener: RecognitionListener, onUnavailable: () -> Unit) {
        val active = recognizer ?: createRecognizer(context, listener)
        if (active == null) {
            markBroken()
            onUnavailable()
            return
        }
        recognizer = active
        readyForSpeech = false
        userStopped = false
        state.message = "Listening…"
        state.listening = true
        try {
            active.startListening(recognizerIntent(context))
        } catch (e: RuntimeException) {
            // SecurityException / IllegalStateException from a service that can't be bound.
            state.listening = false
            state.message = null
            markBroken()
            onUnavailable()
        }
    }

    fun stop(state: VoiceSearchState) {
        userStopped = true
        recognizer?.stopListening()
        state.listening = false
    }

    fun markBroken() {
        inAppBroken = true
        release()
    }

    fun release() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun createRecognizer(context: Context, listener: RecognitionListener): SpeechRecognizer? = try {
        SpeechRecognizer.createSpeechRecognizer(context).apply { setRecognitionListener(listener) }
    } catch (e: RuntimeException) {
        null
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
    holder: RecognizerHolder,
    onPartial: (String) -> Unit,
    onResult: (String) -> Unit,
    onBindFailure: () -> Unit,
) = object : RecognitionListener {
    override fun onReadyForSpeech(params: Bundle?) {
        holder.readyForSpeech = true
    }

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
        if (!holder.userStopped && isRecognizerBindFailure(error, holder.readyForSpeech)) {
            state.message = null
            holder.markBroken()
            onBindFailure()
        } else {
            state.message = voiceErrorMessage(error)
        }
    }
}

/** The system speech activity request (Fire OS voice UI): free-form, with an on-screen prompt. */
private fun systemSpeechIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    putExtra(RecognizerIntent.EXTRA_PROMPT, SYSTEM_SPEECH_PROMPT)
    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, SYSTEM_SPEECH_MAX_RESULTS)
}

/** Visible on Android 11+ / Fire OS 8+ through the manifest's `RECOGNIZE_SPEECH` `<queries>` entry. */
private fun hasSystemSpeechActivity(context: Context): Boolean {
    @Suppress("DEPRECATION") // the flags overload needs API 33; minSdk is 26
    val matches = context.packageManager.queryIntentActivities(systemSpeechIntent(), PackageManager.MATCH_DEFAULT_ONLY)
    return matches.isNotEmpty()
}

/** Returns whether the activity was started (it can vanish or refuse between the query and the launch). */
private fun launchSystemSpeech(launcher: ActivityResultLauncher<Intent>): Boolean = try {
    launcher.launch(systemSpeechIntent())
    true
} catch (e: ActivityNotFoundException) {
    false
} catch (e: SecurityException) {
    false
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
private const val SYSTEM_SPEECH_PROMPT = "Search movies & TV"
private const val SYSTEM_SPEECH_MAX_RESULTS = 5
