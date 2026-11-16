package com.seastreams.tv.ui.screens.search

import android.speech.SpeechRecognizer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class VoiceSearchTest {

    @Test
    fun `recognizer errors map to actionable messages`() {
        assertEquals(PERMISSION_MESSAGE, voiceErrorMessage(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))
        assertEquals(voiceErrorMessage(SpeechRecognizer.ERROR_NO_MATCH), voiceErrorMessage(SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
        assertEquals(voiceErrorMessage(SpeechRecognizer.ERROR_NETWORK), voiceErrorMessage(SpeechRecognizer.ERROR_SERVER))
        assertNotEquals(voiceErrorMessage(SpeechRecognizer.ERROR_AUDIO), voiceErrorMessage(SpeechRecognizer.ERROR_NO_MATCH))
    }
}
