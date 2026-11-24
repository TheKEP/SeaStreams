package com.seastreams.tv.ui.screens.splash

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SplashMotionTest {

    private fun motion(
        scale: Float = 1f,
        manufacturer: String = "Google",
        isFireTv: Boolean = false,
        isLowRam: Boolean = false,
    ) = splashMotionFor(scale, manufacturer, isFireTv, isLowRam)

    @Test
    fun `regular Google TV and onn hardware with animations on gets the wave`() {
        for (manufacturer in listOf("Google", "onn", "Walmart")) {
            for (scale in listOf(1f, 0.5f, 2f)) {
                assertEquals(SplashMotion.Wave, motion(scale = scale, manufacturer = manufacturer), "$manufacturer @ $scale")
            }
        }
    }

    @Test
    fun `animations turned off at the OS level fall back to the fade`() {
        assertEquals(SplashMotion.Fade, motion(scale = 0f))
    }

    @Test
    fun `Amazon manufacturer is matched case-insensitively`() {
        for (manufacturer in listOf("Amazon", "amazon", "AMAZON")) {
            assertEquals(SplashMotion.Fade, motion(manufacturer = manufacturer), manufacturer)
        }
    }

    @Test
    fun `Fire TV system feature catches OEM Fire TV sets that report their own manufacturer`() {
        assertEquals(SplashMotion.Fade, motion(manufacturer = "Insignia", isFireTv = true))
        assertEquals(SplashMotion.Wave, motion(manufacturer = "Insignia", isFireTv = false))
    }

    @Test
    fun `low-RAM devices fall back to the fade`() {
        assertEquals(SplashMotion.Fade, motion(isLowRam = true))
    }

    @Test
    fun `manufacturer match is whole-name, not substring`() {
        assertEquals(SplashMotion.Wave, motion(manufacturer = "NotAmazon"))
        assertEquals(SplashMotion.Wave, motion(manufacturer = ""))
    }
}
