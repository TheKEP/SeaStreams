package com.seastreams.tv.ui.screens.splash

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.seastreams.tv.R
import com.seastreams.tv.ui.theme.FredokaFontFamily
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

private const val WORDMARK = "seastreams"

/** Height of the crest (6dp up, 6dp down). */
private const val WAVE_AMPLITUDE_DP = 6f

/** One letter's full rise-and-fall, long enough that 6–7 letters roll together. */
internal const val LETTER_BOUNCE_MS = 520

/** The letter to the left starts this long after its right neighbour, giving an overlapping swell. */
internal const val STAGGER_MS = 65

/** Rest after the leftmost `s` settles, before the next sweep. */
private const val PAUSE_MS = 1500L

/** The splash never leaves before this, even if the profile gate has already loaded. */
private const val MIN_HOLD_MS = 3000L

/**
 * Vertical offset in dp of the letter at [index] when the sweep is [sweepMs] in. The wave runs
 * right-to-left: the last letter starts at 0ms and each letter to its left [STAGGER_MS] later.
 * Within its [LETTER_BOUNCE_MS] window a letter rises (negative = up), dips, and settles, shaped
 * by a `sin(πt)` envelope; outside the window it is at rest.
 */
internal fun waveOffsetDp(sweepMs: Float, index: Int, lastIndex: Int): Float {
    val localElapsed = sweepMs - (lastIndex - index) * STAGGER_MS
    if (localElapsed !in 0f..LETTER_BOUNCE_MS.toFloat()) return 0f
    val t = localElapsed / LETTER_BOUNCE_MS
    val envelope = sin(PI.toFloat() * t)
    val wave = sin(2f * PI.toFloat() * t)
    return -WAVE_AMPLITUDE_DP * envelope * wave
}

/**
 * Startup splash shown before the Profile Gate (PRD §2): the SeaStreams icon and a Fredoka
 * `seastreams` wordmark with a looping right-to-left ocean wave on pure black. It finishes once
 * at least [MIN_HOLD_MS] has passed *and* [isStartupReady] is true, whichever comes last.
 */
@Composable
fun SeaStreamsSplashScreen(
    isStartupReady: Boolean,
    onSplashFinished: () -> Unit,
) {
    // Consume Back during the hold so the remote can't exit the app mid-splash.
    BackHandler(enabled = true) {}

    val letters = remember { WORDMARK.toList() }
    val lastIndex = letters.lastIndex
    val totalSweepMs = LETTER_BOUNCE_MS + lastIndex * STAGGER_MS
    val sweepProgressMs = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        while (isActive) {
            sweepProgressMs.snapTo(0f)
            sweepProgressMs.animateTo(
                targetValue = totalSweepMs.toFloat(),
                animationSpec = tween(durationMillis = totalSweepMs, easing = LinearEasing),
            )
            delay(PAUSE_MS)
        }
    }

    // Single timer from first frame: hold MIN_HOLD_MS, then wait (if still needed) for readiness.
    val ready by rememberUpdatedState(isStartupReady)
    val finish by rememberUpdatedState(onSplashFinished)
    LaunchedEffect(Unit) {
        delay(MIN_HOLD_MS)
        snapshotFlow { ready }.first { it }
        finish()
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_seastreams_icon),
                contentDescription = "SeaStreams Logo",
                modifier = Modifier.size(96.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                letters.forEachIndexed { index, char ->
                    Text(
                        text = char.toString(),
                        color = Color.White,
                        fontSize = 64.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FredokaFontFamily,
                        // Read the animation in the layout phase so frames don't recompose the Texts.
                        modifier = Modifier.offset {
                            val dp = waveOffsetDp(sweepProgressMs.value, index, lastIndex)
                            IntOffset(0, dp.dp.toPx().roundToInt())
                        },
                    )
                }
            }
        }
    }
}
