package com.seastreams.tv.ui.screens.search

import android.app.Activity
import android.speech.SpeechRecognizer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VoiceSearchRoutingTest {

    private class RecordingActions : VoiceSearchActions {
        val calls = mutableListOf<String>()
        override fun submit(text: String) {
            calls += "submit:$text"
        }
        override fun focusMic() {
            calls += "focusMic"
        }
        override fun focusAndOpenKeyboard() {
            calls += "focusAndOpenKeyboard"
        }
    }

    @Test
    fun `in-app recognizer wins whenever it is available and unbroken`() {
        assertEquals(VoiceBackend.IN_APP, chooseVoiceBackend(inAppAvailable = true, inAppBroken = false, systemActivityAvailable = true))
        assertEquals(VoiceBackend.IN_APP, chooseVoiceBackend(inAppAvailable = true, inAppBroken = false, systemActivityAvailable = false))
    }

    @Test
    fun `no recognition service falls back to the system speech activity`() {
        assertEquals(VoiceBackend.SYSTEM_ACTIVITY, chooseVoiceBackend(inAppAvailable = false, inAppBroken = false, systemActivityAvailable = true))
    }

    @Test
    fun `a recognizer that failed to bind falls back to the system speech activity`() {
        assertEquals(VoiceBackend.SYSTEM_ACTIVITY, chooseVoiceBackend(inAppAvailable = true, inAppBroken = true, systemActivityAvailable = true))
    }

    @Test
    fun `neither path available selects none`() {
        assertEquals(VoiceBackend.NONE, chooseVoiceBackend(inAppAvailable = false, inAppBroken = false, systemActivityAvailable = false))
        assertEquals(VoiceBackend.NONE, chooseVoiceBackend(inAppAvailable = true, inAppBroken = true, systemActivityAvailable = false))
    }

    @Test
    fun `only client and disconnect errors before ready count as bind failures`() {
        assertTrue(isRecognizerBindFailure(SpeechRecognizer.ERROR_CLIENT, readyForSpeech = false))
        assertTrue(isRecognizerBindFailure(SpeechRecognizer.ERROR_SERVER_DISCONNECTED, readyForSpeech = false))
        assertFalse(isRecognizerBindFailure(SpeechRecognizer.ERROR_CLIENT, readyForSpeech = true))
        assertFalse(isRecognizerBindFailure(SpeechRecognizer.ERROR_NO_MATCH, readyForSpeech = false))
        assertFalse(isRecognizerBindFailure(SpeechRecognizer.ERROR_NETWORK, readyForSpeech = false))
    }

    @Test
    fun `ok result yields the top non-blank transcript trimmed`() {
        assertEquals(
            SystemVoiceOutcome.Transcript("star wars"),
            systemVoiceOutcome(Activity.RESULT_OK, listOf("", "   ", "  star wars ", "star war")),
        )
    }

    @Test
    fun `ok result without a usable transcript is no match`() {
        assertEquals(SystemVoiceOutcome.NoMatch, systemVoiceOutcome(Activity.RESULT_OK, emptyList()))
        assertEquals(SystemVoiceOutcome.NoMatch, systemVoiceOutcome(Activity.RESULT_OK, null))
        assertEquals(SystemVoiceOutcome.NoMatch, systemVoiceOutcome(Activity.RESULT_OK, listOf(" ")))
    }

    @Test
    fun `canceled result is a cancellation even with data`() {
        assertEquals(SystemVoiceOutcome.Cancelled, systemVoiceOutcome(Activity.RESULT_CANCELED, null))
        assertEquals(SystemVoiceOutcome.Cancelled, systemVoiceOutcome(Activity.RESULT_CANCELED, listOf("star wars")))
    }

    @Test
    fun `transcript submits the search then refocuses the mic`() {
        val state = VoiceSearchState().apply { message = "old" }
        val actions = RecordingActions()
        applySystemVoiceOutcome(SystemVoiceOutcome.Transcript("star wars"), state, actions)
        assertEquals(listOf("submit:star wars", "focusMic"), actions.calls)
        assertNull(state.message)
        assertFalse(state.listening)
    }

    @Test
    fun `cancellation shows no error and refocuses the mic`() {
        val state = VoiceSearchState().apply { message = "old" }
        val actions = RecordingActions()
        applySystemVoiceOutcome(SystemVoiceOutcome.Cancelled, state, actions)
        assertEquals(listOf("focusMic"), actions.calls)
        assertNull(state.message)
    }

    @Test
    fun `no match shows the retry hint without submitting`() {
        val state = VoiceSearchState()
        val actions = RecordingActions()
        applySystemVoiceOutcome(SystemVoiceOutcome.NoMatch, state, actions)
        assertEquals(listOf("focusMic"), actions.calls)
        assertEquals(voiceErrorMessage(SpeechRecognizer.ERROR_NO_MATCH), state.message)
    }

    @Test
    fun `the mic shows only where a recognition service or speech activity exists`() {
        assertTrue(isVoiceInputAvailable(inAppAvailable = true, systemActivityAvailable = false))
        assertTrue(isVoiceInputAvailable(inAppAvailable = false, systemActivityAvailable = true))
        assertTrue(isVoiceInputAvailable(inAppAvailable = true, systemActivityAvailable = true))
        // Fire OS: neither, so VoiceBackend.NONE and no Microphone.
        assertFalse(isVoiceInputAvailable(inAppAvailable = false, systemActivityAvailable = false))
    }

    @Test
    fun `a runtime dead end hides the mic, notes it and opens the keyboard`() {
        val state = VoiceSearchState().apply { listening = true }
        val actions = RecordingActions()
        applyNoVoiceBackend(state, actions)
        assertEquals(listOf("focusAndOpenKeyboard"), actions.calls)
        assertEquals(VOICE_UNAVAILABLE_MESSAGE, state.message)
        assertFalse(state.micAvailable)
        assertFalse(state.listening)
    }

    @Test
    fun `no remote mic hint is ever shown`() {
        val state = VoiceSearchState()
        applyNoVoiceBackend(state, RecordingActions())
        assertFalse(state.message.orEmpty().contains("remote", ignoreCase = true))
    }

    @Test
    fun `voice notice clears on input but other messages stay`() {
        val state = VoiceSearchState()
        applyNoVoiceBackend(state, RecordingActions())
        state.clearVoiceNotice()
        assertNull(state.message)

        val noMatch = voiceErrorMessage(SpeechRecognizer.ERROR_NO_MATCH)
        state.message = noMatch
        state.clearVoiceNotice()
        assertEquals(noMatch, state.message)
    }
}
