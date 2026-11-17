package com.seastreams.tv.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import com.seastreams.tv.core.datastore.BackgroundSwatches
import com.seastreams.tv.ui.theme.FocusBorderWidth
import com.seastreams.tv.ui.theme.FocusPill
import com.seastreams.tv.ui.theme.onDpadEdgeWrap
import com.seastreams.tv.ui.theme.requestFocusAfterFrame
import com.seastreams.tv.ui.theme.tryRequestFocus

/**
 * Visual background picker (PRD §10): one circle per colour, no hex codes. Focusing a swatch previews
 * it as the live page background ([onPreview]); `Enter` saves it ([onConfirm]); `Back`, or moving
 * focus out of the strip without confirming, restores the saved colour ([onCancel]). Its argument says
 * whether focus should go back to the picker's row (`Back`) or already went elsewhere (moved away).
 */
@Composable
fun BackgroundSwatchStrip(
    savedArgb: Int,
    onPreview: (Int) -> Unit,
    onConfirm: (Int) -> Unit,
    onCancel: (refocusRow: Boolean) -> Unit,
) {
    val requesters = remember { BackgroundSwatches.ALL.map { FocusRequester() } }
    var hadFocus by remember { mutableStateOf(false) }
    var confirmed by remember { mutableStateOf(false) }
    BackHandler { onCancel(true) }
    LaunchedEffect(Unit) {
        val savedIndex = BackgroundSwatches.ALL.indexOf(savedArgb).coerceAtLeast(0)
        requesters[savedIndex].requestFocusAfterFrame()
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 64.dp, top = 4.dp, bottom = 8.dp)
            .onFocusChanged { focus ->
                if (focus.hasFocus) {
                    hadFocus = true
                } else if (hadFocus && !confirmed) {
                    hadFocus = false
                    onCancel(false)
                }
            }
            // One focus group, so moving between swatches never reads as "focus left the strip".
            .focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BackgroundSwatches.ALL.forEachIndexed { index, argb ->
            ColorSwatch(
                color = Color(argb),
                name = backgroundName(argb),
                saved = argb == savedArgb,
                onFocused = { onPreview(argb) },
                onClick = {
                    confirmed = true
                    onConfirm(argb)
                },
                modifier = Modifier
                    .focusRequester(requesters[index])
                    .then(
                        if (index == BackgroundSwatches.ALL.lastIndex) {
                            Modifier.onDpadEdgeWrap(Key.DirectionRight) { requesters.first().tryRequestFocus() }
                        } else {
                            Modifier
                        },
                    ),
            )
        }
    }
}

/** A round colour swatch; [saved] shows a check mark. Shared with the subtitle colour picker. */
@Composable
internal fun ColorSwatch(color: Color, name: String, saved: Boolean, onFocused: () -> Unit, onClick: () -> Unit, modifier: Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .size(SwatchSize)
            .semantics { contentDescription = name }
            .onFocusChanged { if (it.isFocused) onFocused() },
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.15f),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.35f)), shape = CircleShape),
            focusedBorder = Border(BorderStroke(FocusBorderWidth, FocusPill), shape = CircleShape),
        ),
        colors = ClickableSurfaceDefaults.colors(containerColor = color, focusedContainerColor = color),
    ) {
        Box(Modifier.fillMaxSize().background(color, CircleShape), contentAlignment = Alignment.Center) {
            if (saved) Icon(Icons.Filled.Check, contentDescription = "Current", tint = Color.White, modifier = Modifier.size(22.dp))
        }
    }
}

private val SwatchSize = 48.dp
