package com.seastreams.tv.core.network

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guards the platform policy that lets cleartext `http://` addons, streams and subtitles through
 * (targetSdk 28+ blocks cleartext unless a network security config permits it).
 * Unit tests run with the `app` module as the working directory.
 */
class NetworkSecurityConfigTest {

    private fun parse(path: String): Element {
        val file = File(path)
        assertTrue(file.isFile, "Missing ${file.absolutePath}")
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return factory.newDocumentBuilder().parse(file).documentElement
    }

    private fun Element.children(tag: String): List<Element> {
        val nodes = getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    @Test
    fun `manifest wires the network security config`() {
        val application = parse("src/main/AndroidManifest.xml").children("application").single()
        assertEquals(
            "@xml/network_security_config",
            application.getAttributeNS(ANDROID_NS, "networkSecurityConfig"),
        )
    }

    @Test
    fun `base config permits cleartext and trusts only system CAs`() {
        val root = parse("src/main/res/xml/network_security_config.xml")
        assertEquals("network-security-config", root.tagName)
        val baseConfig = root.children("base-config").single()
        assertEquals("true", baseConfig.getAttribute("cleartextTrafficPermitted"))
        val sources = baseConfig.children("certificates").map { it.getAttribute("src") }
        assertEquals(listOf("system"), sources)
        assertTrue(root.children("domain-config").none { it.getAttribute("cleartextTrafficPermitted") == "false" })
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
