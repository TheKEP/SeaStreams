package com.seastreams.tv.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.seastreams.tv.domain.model.MediaItem
import com.seastreams.tv.ui.theme.FocusPill
import com.seastreams.tv.ui.theme.Obsidian
import com.seastreams.tv.ui.theme.OnFocusPill
import com.seastreams.tv.ui.theme.SurfaceCard
import com.seastreams.tv.ui.theme.TextSecondary
import com.seastreams.tv.ui.theme.focusRing
import com.seastreams.tv.ui.theme.onDpadKeyDown
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest

val HeroHeight = 300.dp
private val HeroShape = RoundedCornerShape(20.dp)
val HeroImageSize = DpSize(864.dp, HeroHeight)
val HeroLogoSize = DpSize(280.dp, 80.dp)

/**
 * Which hero item is showing, plus a counter of direct user interactions with the hero. Hoisted to
 * the screen (see [rememberHeroCarouselState]) so the position and the auto-cycle survive the hero
 * being scrolled out of a lazy list.
 */
@Stable
class HeroCarouselState(val itemCount: Int) {
    var index by mutableIntStateOf(0)
        private set

    /** Bumped on every direct interaction; each change restarts the auto-advance countdown. */
    var interactions by mutableIntStateOf(0)
        private set

    /** Timer tick: next item, wrapping from the last back to the first. */
    fun advance() {
        index = heroIndexAfter(index, 1, itemCount)
    }

    /** D-pad Left/Right on the hero: move by [delta] and restart the countdown. */
    fun userStep(delta: Int) {
        index = heroIndexAfter(index, delta, itemCount)
        interactions++
    }

    /** Any other direct interaction (e.g. pressing the button): restart the countdown. */
    fun userInteracted() {
        interactions++
    }
}

/** [index] moved by [delta] within `0 until size`, wrapping at both ends. */
fun heroIndexAfter(index: Int, delta: Int, size: Int): Int = if (size <= 0) 0 else Math.floorMod(index + delta, size)

/** A fresh carousel state (back at item #1) whenever a new set of hero [items] arrives. */
@Composable
fun rememberHeroCarouselState(items: List<MediaItem>): HeroCarouselState = remember(items) { HeroCarouselState(items.size) }

/**
 * Advances [state] every [HERO_AUTO_ADVANCE_MILLIS] while the host is RESUMED, wherever focus is.
 * Pauses on ON_PAUSE/ON_STOP (a fresh countdown starts on resume) and stops when the calling screen
 * leaves composition. Call it from the screen, not from inside a lazy list item.
 */
@Composable
fun HeroAutoCycleEffect(state: HeroCarouselState) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(state, lifecycle) {
        if (state.itemCount < 2) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            runHeroAutoCycle(snapshotFlow { state.interactions }, HERO_AUTO_ADVANCE_MILLIS, state::advance)
        }
    }
}

/**
 * Calls [advance] every [intervalMillis] until cancelled. Each new value from [resets] cancels the
 * running countdown and starts a full new one.
 */
internal suspend fun runHeroAutoCycle(resets: Flow<Int>, intervalMillis: Long, advance: () -> Unit) {
    resets.collectLatest {
        while (true) {
            delay(intervalMillis)
            advance()
        }
    }
}

/**
 * Full-width featured card (`home-hero.png`). The only focus target is the `View Details` pill;
 * D-pad Left/Right on it cycles through [items], wrapping at both ends. While it holds focus the card
 * shows the catalog-card focus ring. Auto-advance is driven by [HeroAutoCycleEffect] on [state].
 */
@Composable
fun HeroCarousel(
    state: HeroCarouselState,
    items: List<MediaItem>,
    buttonFocusRequester: FocusRequester,
    onViewDetails: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val index = state.index.coerceIn(items.indices)
    val current = items[index]
    var focused by remember { mutableStateOf(false) }
    val step: (Int) -> Boolean = { delta ->
        state.userStep(delta)
        true
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(HeroHeight)
            .onFocusChanged { focused = it.hasFocus }
            .clip(HeroShape)
            .background(SurfaceCard)
            .focusRing(focused, HeroShape),
    ) {
        Crossfade(targetState = current, animationSpec = tween(HERO_FADE_MILLIS), label = "heroBackdrop") { item ->
            SizedImage(url = item.heroBackdropUrl, size = HeroImageSize, contentDescription = null, modifier = Modifier.fillMaxSize())
        }
        // Left/bottom scrim so the text stays legible over bright artwork.
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to Obsidian.copy(alpha = 0.85f), 0.55f to Color.Transparent),
            ),
        )
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = 40.dp, bottom = 28.dp).width(460.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TitleArt(current, logoSize = HeroLogoSize, textStyle = MaterialTheme.typography.displaySmall)
            MediaMetadataLine(current, showCertification = true)
            if (current.overview.isNotBlank()) {
                Text(
                    current.overview,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Button(
                onClick = {
                    state.userInteracted()
                    onViewDetails(current)
                },
                modifier = Modifier
                    .focusRequester(buttonFocusRequester)
                    .onDpadKeyDown(Key.DirectionLeft) { step(-1) }
                    .onDpadKeyDown(Key.DirectionRight) { step(1) },
                colors = ButtonDefaults.colors(
                    containerColor = Color.White.copy(alpha = 0.18f),
                    contentColor = Color.White,
                    focusedContainerColor = FocusPill,
                    focusedContentColor = OnFocusPill,
                ),
            ) {
                Text("View Details", fontWeight = FontWeight.SemiBold)
            }
        }
        if (items.size > 1) PageDots(count = items.size, selected = index, Modifier.align(Alignment.BottomEnd))
    }
}

/** Non-focusable stand-in shown while the hero loads or when it could not be loaded. */
@Composable
fun HeroPlaceholder(message: String?, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().height(HeroHeight).clip(HeroShape).background(SurfaceCard),
        contentAlignment = Alignment.Center,
    ) {
        if (message != null) Text(message, style = MaterialTheme.typography.bodyLarge, color = TextSecondary)
    }
}

/** Position indicator: dots for short carousels, a `3 / 40` counter for long ones. */
@Composable
private fun PageDots(count: Int, selected: Int, modifier: Modifier) {
    if (count > MAX_DOTS) {
        Text(
            "${selected + 1} / $count",
            style = MaterialTheme.typography.labelLarge,
            color = TextSecondary,
            modifier = modifier.padding(end = 28.dp, bottom = 28.dp),
        )
        return
    }
    Row(modifier.padding(end = 28.dp, bottom = 32.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { i ->
            Box(
                Modifier
                    .size(width = if (i == selected) 18.dp else 6.dp, height = 6.dp)
                    .clip(CircleShape)
                    .background(if (i == selected) FocusPill else FocusPill.copy(alpha = 0.35f)),
            )
        }
    }
}

/** How long each hero item shows before the carousel advances on its own (PRD §3). */
const val HERO_AUTO_ADVANCE_MILLIS = 5_000L

private const val HERO_FADE_MILLIS = 350
private const val MAX_DOTS = 12
