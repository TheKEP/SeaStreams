package com.seastreams.tv.ui.screens.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.os.Build
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * When the player leaves composition, puts the host window's HDR-related state back to its defaults
 * (PRD §10): `COLOR_MODE_DEFAULT`, no brightness override and, on API 35+, no desired HDR headroom.
 * SeaStreams never sets these, so this is a guarantee that every non-player screen stays SDR even if
 * something else changed them, not the HDR teardown itself (that is the surface detach on exit).
 */
@Composable
fun ResetHdrWindowOnExit() {
    val view = LocalView.current
    DisposableEffect(view) {
        onDispose { view.context.findActivity()?.window?.let(::resetHdrWindow) }
    }
}

/** Whether [colorMode] / [screenBrightness] differ from a plain SDR window's. */
internal fun windowResetNeeded(colorMode: Int, screenBrightness: Float): Boolean =
    colorMode != ActivityInfo.COLOR_MODE_DEFAULT || screenBrightness != WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE

private fun resetHdrWindow(window: Window) {
    val attributes = window.attributes
    if (windowResetNeeded(window.colorMode, attributes.screenBrightness)) {
        window.colorMode = ActivityInfo.COLOR_MODE_DEFAULT
        attributes.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = attributes
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && window.desiredHdrHeadroom != 0f) {
        window.desiredHdrHeadroom = 0f
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
