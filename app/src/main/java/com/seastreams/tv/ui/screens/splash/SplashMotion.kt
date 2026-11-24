package com.seastreams.tv.ui.screens.splash

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** `PackageManager` feature every Fire TV device declares, whatever OEM name it reports. */
private const val FIRE_TV_FEATURE = "amazon.hardware.fire_tv"

/** How the splash lockup comes on screen. */
internal enum class SplashMotion {
    /** Full per-letter pop-in and bobbing wave. */
    Wave,

    /** One whole-lockup fade, or an immediate reveal when animations are off. */
    Fade,
}

/**
 * Picks [SplashMotion.Fade] when the OS has animations turned off ([animatorDurationScale] `0`) or
 * on Fire OS / low-RAM hardware, where the 10-letter wave drops frames during cold start. Fire OS is
 * detected by [manufacturer] `Amazon` or by the Fire TV system feature ([isFireTv]), because
 * OEM Fire TV sets such as Insignia may report their own manufacturer name.
 */
internal fun splashMotionFor(
    animatorDurationScale: Float,
    manufacturer: String,
    isFireTv: Boolean,
    isLowRamDevice: Boolean,
): SplashMotion {
    val lightweight = animatorDurationScale == 0f ||
        manufacturer.equals("Amazon", ignoreCase = true) ||
        isFireTv ||
        isLowRamDevice
    return if (lightweight) SplashMotion.Fade else SplashMotion.Wave
}

/** The OS animator duration scale (`0` = animations off / reduced motion). */
internal fun animatorDurationScale(context: Context): Float =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)

/** Reads the device signals once per splash and picks its [SplashMotion]. */
@Composable
internal fun rememberSplashMotion(): SplashMotion {
    val context = LocalContext.current
    return remember(context) {
        splashMotionFor(
            animatorDurationScale = animatorDurationScale(context),
            manufacturer = Build.MANUFACTURER.orEmpty(),
            isFireTv = context.packageManager.hasSystemFeature(FIRE_TV_FEATURE),
            isLowRamDevice = context.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true,
        )
    }
}
