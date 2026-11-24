package com.seastreams.tv.core.webserver

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The portal is one page whose scripts ([PortalScript], [PortalUsenetScript], [PortalDebridScript], [PortalStreamScript])
 * share a single `<script>` block, so one syntax error anywhere leaves the whole page blank: no profiles, no forms.
 * These checks run on the HTML exactly as [PortalHtmlTemplate.render] serves it.
 */
class PortalScriptSyntaxTest {

    private val html = PortalHtmlTemplate.render()

    private val scripts: List<String> = SCRIPT_BLOCK.findAll(html).map { it.groupValues[1] }.toList()

    @Test
    fun `the page has its script block with every portal script inside`() {
        assertEquals(1, scripts.size)
        val script = scripts.single()
        assertFalse(script.contains("/*SCRIPT*/") || script.contains("/*AVATARS*/"), "placeholders left unreplaced")
        listOf("function render(", "function setupStreams(", "function exportStreams(", "function importStreams(", "window.AVATARS = [")
            .forEach { assertTrue(script.contains(it), it) }
    }

    @Test
    fun `the served script tokenises cleanly`() {
        val errors = JsSyntaxChecker.check(scripts.single())
        assertTrue(errors.isEmpty(), errors.joinToString("\n"))
    }

    @Test
    fun `each portal script tokenises cleanly on its own`() {
        mapOf(
            "PortalScript" to PortalScript.JS,
            "PortalUsenetScript" to PortalUsenetScript.JS,
            "PortalDebridScript" to PortalDebridScript.JS,
            "PortalStreamScript" to PortalStreamScript.JS,
        ).forEach { (name, js) ->
            val errors = JsSyntaxChecker.check(js)
            assertTrue(errors.isEmpty(), "$name:\n" + errors.joinToString("\n"))
        }
    }

    @Test
    fun `no Kotlin interpolation artifacts reach the page`() {
        listOf(PortalScript.JS, PortalUsenetScript.JS, PortalDebridScript.JS, PortalStreamScript.JS).forEach { js ->
            // The scripts are Kotlin raw strings: a dollar sign would be interpolated, so none may appear at all.
            assertFalse(js.contains('$'), js.lines().firstOrNull { it.contains('$') })
        }
        val script = scripts.single()
        listOf("\${", "kotlin.Unit", "Function0<", "Function1<").forEach { artifact ->
            assertFalse(script.contains(artifact), artifact)
        }
        // An interpolated object reference (`com.seastreams….Foo@1a2b3c`) would land in the page as text.
        assertFalse(Regex("com\\.seastreams\\.[\\w.]+@[0-9a-f]+").containsMatchIn(html))
    }

    @Test
    fun `every element id the scripts look up literally exists in the page or is created by a script`() {
        val script = scripts.single()
        val looked = BY_ID.findAll(script).map { it.groupValues[1] }.toSet()
        // Ids are also created through helpers (spArea('sp-fm-name', 3), field('up-name', …)), so any string literal outside
        // a byId() lookup counts as a creator; a byId() typo still has nothing to match.
        val created = SINGLE_QUOTED.findAll(BY_ID.replace(script, "")).map { it.groupValues[1] }.toSet()
        val available = HTML_ID.findAll(html).map { it.groupValues[1] }.toSet() + created + CONCATENATED_IDS
        val missing = looked - available
        assertTrue(missing.isEmpty(), "byId() targets nothing creates: $missing")
        // The stream backup controls in particular.
        assertTrue(listOf("sp-export", "sp-import", "sp-import-file").all { it in available })
    }

    @Test
    fun `the checker catches the mistakes that blank the page`() {
        // The real regression: an apostrophe inside a single-quoted string.
        val apostrophe = "h('p', { class: 'hint' }, 'Export saves this profile's filters as a JSON file.'),\n"
        assertTrue(JsSyntaxChecker.check("const x = [\n$apostrophe];").isNotEmpty())
        assertTrue(JsSyntaxChecker.check("function f() { return g(1, 2; }").isNotEmpty())
        assertTrue(JsSyntaxChecker.check("const s = \"no end;\nf();").isNotEmpty())
        assertTrue(JsSyntaxChecker.check("const t = `a\nb;").isNotEmpty())
        assertTrue(JsSyntaxChecker.check("const t = `\${x}`;").isNotEmpty())
        assertTrue(JsSyntaxChecker.check("if (a) { b();").isNotEmpty())
        assertTrue(JsSyntaxChecker.check("/* never closed").isNotEmpty())
        // And accepts the constructs the scripts do use.
        val fine = """
            const a = "it's", b = 'say "hi"', c = 'esc\'aped', t = `multi
            line`; // comment with ' and "
            /* block ' " */ const r = x.split(/[,;]\s*/g).map((v) => v / 2);
            function f(n) { return /^\d+$/.test(n) ? n : null; }
        """.trimIndent()
        assertEquals(emptyList<String>(), JsSyntaxChecker.check(fine))
    }

    private companion object {
        val SCRIPT_BLOCK = Regex("<script>([\\s\\S]*?)</script>")
        val BY_ID = Regex("byId\\('([^']+)'\\)")
        val HTML_ID = Regex("\\bid=\"([^\"]+)\"")
        val SINGLE_QUOTED = Regex("'([A-Za-z0-9_-]+)'")

        /** Built as `prefix + '-' + key` (`listGroup('rx', …)` with the `SP_LISTS` keys), so no literal holds them. */
        val CONCATENATED_IDS = setOf("rx-required", "rx-excluded", "rx-included", "rx-preferred")
    }
}
