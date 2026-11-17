package com.seastreams.tv.domain.filter

import com.seastreams.tv.player.subtitle.CleanSdhTextRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class SdhSubtitleCleanerTest {

    private fun clean(text: String) = SdhSubtitleCleaner.clean(text)

    @ParameterizedTest
    @ValueSource(strings = ["[SIGHS]", "(DOOR CREAKS)", "[man on radio]", "♪", "♪ ♪", "[GUNSHOTS] (SCREAMING)", "  ", "- [GASPS]"])
    fun `cues made only of annotations become empty`(cue: String) {
        assertEquals("", clean(cue))
    }

    @Test
    fun `bracketed and parenthetical cues are removed around dialogue`() {
        assertEquals("I can't do this.", clean("[SIGHS] I can't do this."))
        assertEquals("Get out!", clean("Get out! (DOOR SLAMS)"))
        assertEquals("Wait for me.", clean("Wait (PANTING) for me."))
    }

    @Test
    fun `speaker labels are stripped, including after a dialogue dash`() {
        assertEquals("Where were you?", clean("JOHN: Where were you?"))
        assertEquals("- Hi.\n- Hello.", clean("- MARY: Hi.\n- DR. SMITH: Hello."))
        assertEquals("Stay down.", clean("MAN 2: Stay down."))
    }

    @Test
    fun `a label with a parenthetical is stripped completely`() {
        assertEquals("Previously on…", clean("NARRATOR (V.O.): Previously on…"))
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "Note: bring the keys.|Note: bring the keys.",
            "Meet me at 10:30.|Meet me at 10:30.",
            "I said: no.|I said: no.",
            "A: yes|A: yes",
        ],
    )
    fun `mixed case text, times and single letters are not mistaken for labels`(input: String, expected: String) {
        assertEquals(expected, clean(input))
    }

    @Test
    fun `annotations that wrap across lines are removed and empty lines dropped`() {
        assertEquals("Run!", clean("[THUNDER\nRUMBLING]\nRun!"))
    }

    @Test
    fun `music notes are stripped but sung lyrics kept`() {
        assertEquals("Happy birthday to you", clean("♪ Happy birthday to you ♪"))
        assertEquals("la la", clean("♫ la la ♫"))
    }

    @Test
    fun `nested brackets are removed`() {
        assertEquals("Okay.", clean("[MUFFLED (INDISTINCT) TALKING] Okay."))
    }

    @Test
    fun `plain dialogue passes through unchanged`() {
        val line = "We should go.\nRight now."
        assertEquals(line, clean(line))
    }

    @Test
    fun `whitespace left behind is collapsed`() {
        assertEquals("No way.", clean("No  [LAUGHS]  way."))
    }

    @Test
    fun `the renderer cleans only while Clean SDH is on and hides emptied cues`() {
        val renderer = CleanSdhTextRenderer()
        assertEquals("[SIGHS] Fine.", renderer.render(listOf("[SIGHS] Fine.")))
        renderer.cleanSdh = true
        assertEquals("Fine.", renderer.render(listOf("[SIGHS] Fine.")))
        assertNull(renderer.render(listOf("(DOOR CREAKS)")))
        assertEquals("One\nTwo", renderer.render(listOf("JOHN: One", null, "Two")))
    }
}
