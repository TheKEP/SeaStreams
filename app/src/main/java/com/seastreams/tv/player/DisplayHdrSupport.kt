package com.seastreams.tv.player

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display

/** What the connected display reports it can show (PRD §10 Dolby Vision `Auto`). */
object DisplayHdrSupport {

    /** Whether the default display reports Dolby Vision. Read once per playback session. */
    fun supportsDolbyVision(context: Context): Boolean {
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY) ?: return false
        val types = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            display.mode.supportedHdrTypes
        } else {
            @Suppress("DEPRECATION")
            display.hdrCapabilities?.supportedHdrTypes
        }
        return reportsDolbyVision(types)
    }

    fun reportsDolbyVision(types: IntArray?): Boolean = types?.contains(Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION) == true
}
