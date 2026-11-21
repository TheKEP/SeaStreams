package com.seastreams.tv.ui.screens.player

import android.content.pm.ActivityInfo
import android.view.WindowManager
import com.seastreams.tv.player.DynamicRange
import com.seastreams.tv.ui.components.HDR_UI_WHITE_LEVEL
import com.seastreams.tv.ui.components.hdrDimmedLevel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/** PRD §10: when HDR White Dimming applies, and the exit / window state that keeps the TV in SDR. */
class PlayerHdrStateTest {

    @ParameterizedTest
    @EnumSource(DynamicRange::class)
    fun `the UI dims only with the setting on and an HDR or Dolby Vision stream`(range: DynamicRange) {
        assertEquals(range != DynamicRange.SDR, PlayerUiState(hdrWhiteDimming = true, dynamicRange = range).dimUi)
        assertFalse(PlayerUiState(hdrWhiteDimming = false, dynamicRange = range).dimUi)
    }

    @Test
    fun `a new session starts SDR, undimmed and with its video attached`() {
        val fresh = PlayerUiState()
        assertEquals(DynamicRange.SDR, fresh.dynamicRange)
        assertFalse(fresh.dimUi)
        assertFalse(fresh.videoDetached)
    }

    @Test
    fun `dimmed whites keep 75 percent and black stays black`() {
        assertEquals(0.75f, HDR_UI_WHITE_LEVEL)
        assertEquals(0.75f, hdrDimmedLevel(1f))
        assertEquals(0f, hdrDimmedLevel(0f))
        assertEquals(0.375f, hdrDimmedLevel(0.5f))
    }

    @Test
    fun `a default SDR window needs no reset`() {
        assertFalse(windowResetNeeded(ActivityInfo.COLOR_MODE_DEFAULT, WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE))
    }

    @Test
    fun `an HDR colour mode or a brightness override is reset`() {
        assertTrue(windowResetNeeded(ActivityInfo.COLOR_MODE_HDR, WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE))
        assertTrue(windowResetNeeded(ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT, WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE))
        assertTrue(windowResetNeeded(ActivityInfo.COLOR_MODE_DEFAULT, WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL))
        assertTrue(windowResetNeeded(ActivityInfo.COLOR_MODE_DEFAULT, 0.4f))
    }
}
