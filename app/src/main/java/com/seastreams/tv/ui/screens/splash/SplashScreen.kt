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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.seastreams.tv.R
import com.seastreams.tv.ui.theme.FredokaFontFamily
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

private const val WORDMARK = "seastreams"

/** One-shot motion length: every letter has popped in, bobbed, and is completely still by 2840ms. */
internal const val ANIMATION_DURATION_MS = 2840f

/**
 * Black, motionless frames before the wave starts. On a cold start Android TV is still fading out
 * the launcher and starting app services, and the first ~300ms of frames drop; waiting until the
 * launch crossfade and startup work have finished lets frame 1 of the wave render smoothly.
 */
private const val START_DELAY_MS = 420L

/** 420ms start delay + 2840ms of motion + ~90ms of stillness; the splash never leaves before this. */
private const val MIN_HOLD_MS = 3350L

/** Each letter to the right starts this long after its left neighbour (9 × 110ms = 990ms). */
internal const val STAGGER_IN_MS = 110f

/** One letter's pop-in plus bobbing (990ms + 1850ms = 2840ms). */
internal const val LETTER_LIFE_MS = 1850f

/** Fraction of a letter's life spent popping in; the rest is bobbing and settling. */
internal const val POP_IN_FRACTION = 0.26f

/** Rotation phase lag of the bobbing rock, so the tilt trails the vertical bob slightly. */
private const val ROCK_PHASE = 0.4f

/** Peak crest height at the end of the pop-in, where the bobbing begins. */
private const val CREST_DP = 13f

/** Peak tilt at the crest. */
private const val TILT_DEG = 11f

/**
 * Bobbing decay rate. 0.9 rather than the faster 1.55 first specified: the faster rate only gave
 * ~6dp / ~1.7dp bobs, too small to read as buoyant at TV distance.
 */
private const val BOB_DECAY = 0.9f

/**
 * Icon size and a fixed downward shift, tuned against `docs/ui-references/tv_banner.png`: the play
 * button's top corner sits above the `s`, its bottom corner below, and its tip near the `s` waist.
 * The all-x-height wordmark's ink sits below the centre of its line box, hence the shift.
 */
private val ICON_SIZE = 96.dp
private val ICON_OFFSET_Y = 14.dp

/** Wordmark letters. */
private val WordmarkStyle = TextStyle(
    color = Color.White,
    fontSize = 64.sp,
    fontWeight = FontWeight.SemiBold,
    fontFamily = FredokaFontFamily,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

/** Per-letter draw transform at one instant of the splash animation. */
internal data class LetterWaveState(
    val alpha: Float,
    val scale: Float,
    val offsetYDp: Float,
    val rotationDeg: Float,
)

private val HIDDEN = LetterWaveState(alpha = 0f, scale = 0.25f, offsetYDp = 18f, rotationDeg = -14f)
private val AT_REST = LetterWaveState(alpha = 1f, scale = 1f, offsetYDp = 0f, rotationDeg = 0f)

/**
 * The transform of the letter at [index] when the animation is [sweepMs] in. Letters appear
 * left-to-right: each rises boldly out of the water to a crest (pop-in), then bobs like a float on
 * a slow swell (down, back up, ever smaller) with a slightly lagging side-to-side rock, and settles exactly
 * flat [LETTER_LIFE_MS] after it started.
 */
internal fun letterWaveState(sweepMs: Float, index: Int, lastIndex: Int): LetterWaveState {
    if (sweepMs >= ANIMATION_DURATION_MS) return AT_REST
    val local = sweepMs - index * STAGGER_IN_MS
    if (local < 0f) return HIDDEN
    if (local >= LETTER_LIFE_MS) return AT_REST
    val t = local / LETTER_LIFE_MS
    return if (t < POP_IN_FRACTION) popInState(t / POP_IN_FRACTION) else bobState((t - POP_IN_FRACTION) / (1f - POP_IN_FRACTION))
}

/**
 * Pop-in at progress [u] in 0..1: fades in and rises boldly out of the water, ending on the crest
 * (1.12x, 13dp up, tilted right) exactly where [bobState] begins, so there is no jump between phases.
 */
private fun popInState(u: Float): LetterWaveState {
    val easeOut = 1f - (1f - u) * (1f - u)
    val toCrest = sin((PI / 2.0).toFloat() * u)
    return LetterWaveState(
        alpha = (u / 0.40f).coerceIn(0f, 1f),
        scale = 0.25f + 0.87f * easeOut,
        offsetYDp = 18f * (1f - easeOut) - CREST_DP * toCrest,
        rotationDeg = -14f * (1f - easeOut) + TILT_DEG * cos(ROCK_PHASE) * toCrest,
    )
}

/** Bobbing at progress [b] in 0..1: slow decaying swell that reaches exactly 0 at b = 1. */
private fun bobState(b: Float): LetterWaveState {
    val decay = exp(-BOB_DECAY * b) * (1f - b * b)
    val bob = cos(2.5f * PI.toFloat() * b)
    return LetterWaveState(
        alpha = 1f,
        scale = 1f + 0.12f * bob * decay,
        offsetYDp = -CREST_DP * bob * decay,
        rotationDeg = TILT_DEG * cos(2.5f * PI.toFloat() * b + ROCK_PHASE) * decay,
    )
}

/**
 * Startup splash shown before the Profile Gate (PRD §2): the SeaStreams icon and a Fredoka
 * `seastreams` wordmark whose letters pop in left-to-right and bob in the water before settling
 * still, on pure black. It finishes once at least [MIN_HOLD_MS] has passed *and* [isStartupReady]
 * is true, whichever comes last.
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
    val sweepProgressMs = remember { Animatable(0f) }
    val pxPerDp = LocalDensity.current.density

    // One-shot: the motion plays once and comes to rest; nothing loops.
    LaunchedEffect(Unit) {
        delay(START_DELAY_MS)
        sweepProgressMs.animateTo(
            targetValue = ANIMATION_DURATION_MS,
            animationSpec = tween(durationMillis = ANIMATION_DURATION_MS.toInt(), easing = LinearEasing),
        )
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
                contentDescription = "seastreams logo",
                modifier = Modifier.size(ICON_SIZE).offset(y = ICON_OFFSET_Y),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                letters.forEachIndexed { index, char ->
                    Text(
                        text = char.toString(),
                        style = WordmarkStyle,
                        // Read the animation in the draw-layer phase: frames neither recompose
                        // nor re-layout the Texts.
                        modifier = Modifier.graphicsLayer {
                            val state = letterWaveState(sweepProgressMs.value, index, lastIndex)
                            alpha = state.alpha
                            scaleX = state.scale
                            scaleY = state.scale
                            translationY = state.offsetYDp * pxPerDp
                            rotationZ = state.rotationDeg
                        },
                    )
                }
            }
        }
    }
}
