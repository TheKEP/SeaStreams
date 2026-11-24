package com.seastreams.tv.core.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** The shim NewPipe's API 33 URL codec calls are rewritten to must behave exactly like the JDK overloads. */
class UrlCodecCompatTest {

    private val samples = listOf(
        "plain",
        "a b+c%20d",
        "sig=AOq0QJ8wRQIg%3D%3D&n=x_y-z.~",
        "ünïcødé 🎬 日本語",
        "&=?/#:;,@!$'()*",
        "",
    )

    @Test
    fun `encode matches URLEncoder with a Charset`() {
        for (s in samples) {
            assertEquals(URLEncoder.encode(s, StandardCharsets.UTF_8), UrlCodecCompat.encode(s, StandardCharsets.UTF_8), s)
        }
    }

    @Test
    fun `decode matches URLDecoder with a Charset`() {
        for (s in samples) {
            val encoded = URLEncoder.encode(s, StandardCharsets.UTF_8)
            assertEquals(URLDecoder.decode(encoded, StandardCharsets.UTF_8), UrlCodecCompat.decode(encoded, StandardCharsets.UTF_8), s)
        }
        assertEquals("a b c", UrlCodecCompat.decode("a+b%20c", StandardCharsets.UTF_8))
    }

    @Test
    fun `round trip restores the original`() {
        for (s in samples) {
            assertEquals(s, UrlCodecCompat.decode(UrlCodecCompat.encode(s, StandardCharsets.UTF_8), StandardCharsets.UTF_8))
        }
    }
}
