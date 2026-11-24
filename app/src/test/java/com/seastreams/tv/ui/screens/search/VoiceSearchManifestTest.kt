package com.seastreams.tv.ui.screens.search

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guards the package-visibility entries both voice paths need on Android 11+ / Fire OS 8+:
 * `RecognitionService` for the in-app `SpeechRecognizer`, `RECOGNIZE_SPEECH` for the Fire OS
 * system speech activity fallback. Unit tests run with the `app` module as the working directory.
 */
class VoiceSearchManifestTest {

    @Test
    fun `manifest queries declare both voice recognition intents`() {
        val file = File("src/main/AndroidManifest.xml")
        assertTrue(file.isFile, "Missing ${file.absolutePath}")
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val root = factory.newDocumentBuilder().parse(file).documentElement
        val queries = root.getElementsByTagName("queries").let { (0 until it.length).map { i -> it.item(i) as Element } }
        val actions = queries.flatMap { query ->
            val nodes = query.getElementsByTagName("action")
            (0 until nodes.length).map { (nodes.item(it) as Element).getAttributeNS(ANDROID_NS, "name") }
        }
        assertTrue("android.speech.RecognitionService" in actions, "Missing RecognitionService query: $actions")
        assertTrue("android.speech.action.RECOGNIZE_SPEECH" in actions, "Missing RECOGNIZE_SPEECH query: $actions")
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
