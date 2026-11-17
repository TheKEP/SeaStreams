package com.seastreams.tv.ui.components

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties

/**
 * Remembers which focusable opened a full-screen layer (Details, Episodes, Settings, the stream list)
 * so `Back` can hand focus back to that exact card or button.
 *
 * Cards and buttons that open such a layer call [FocusOrigin.mark] when clicked; the shell [take]s the
 * mark when it pushes the layer and requests it again when the layer is popped. The layer underneath
 * stays composed (see [CoverableLayer]), so the marked node is still attached and in place.
 */
@Stable
class FocusOriginTracker {
    private var pending: FocusRequester? = null

    fun mark(requester: FocusRequester) {
        pending = requester
    }

    /** The last mark, cleared so it is used at most once. */
    fun take(): FocusRequester? = pending.also { pending = null }
}

/** `null` outside a profile's shell (e.g. the profile screens), where nothing needs restoring. */
val LocalFocusOrigins = staticCompositionLocalOf<FocusOriginTracker?> { null }

/** A focusable's own [requester] plus [mark], which records it as the origin of what it opens. */
@Stable
class FocusOrigin(val requester: FocusRequester, private val tracker: FocusOriginTracker?) {
    fun mark() {
        tracker?.mark(requester)
    }
}

/** Attach [FocusOrigin.requester] to the focusable and call [FocusOrigin.mark] in its click handler. */
@Composable
fun rememberFocusOrigin(): FocusOrigin {
    val tracker = LocalFocusOrigins.current
    return remember(tracker) { FocusOrigin(FocusRequester(), tracker) }
}

/** True inside a layer that another full-screen layer currently covers (e.g. Home under Details). */
val LocalLayerCovered = compositionLocalOf { false }

/**
 * A full-screen layer that stays composed while covered, so its scroll positions, row state and the
 * card that opened the layer above survive untouched. While [covered] focus can't move into it, and
 * unless [visible] (e.g. dimmed behind the stream list) it isn't drawn either. While uncovered and
 * [trapFocus], D-pad moves can't leave it for a layer below.
 */
@Composable
fun CoverableLayer(covered: Boolean, trapFocus: Boolean, visible: Boolean = !covered, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayerCovered provides covered) {
        Box(
            Modifier
                .fillMaxSize()
                .drawWithContent { if (visible) drawContent() }
                .focusProperties {
                    onEnter = { if (covered) cancelFocusChange() }
                    onExit = { if (!covered && trapFocus && requestedFocusDirection.isDpadMove()) cancelFocusChange() }
                }
                .focusGroup(),
        ) {
            content()
        }
    }
}

private fun FocusDirection.isDpadMove(): Boolean =
    this == FocusDirection.Up || this == FocusDirection.Down || this == FocusDirection.Left || this == FocusDirection.Right
