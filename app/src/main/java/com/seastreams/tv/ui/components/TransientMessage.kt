package com.seastreams.tv.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.seastreams.tv.ui.theme.FocusPill
import com.seastreams.tv.ui.theme.OnFocusPill
import kotlinx.coroutines.delay

/** One short message; [id] makes a repeated text show again. */
data class TransientMessage(val text: String, val id: Long)

/** Snackbar-style notices ("Already in library", "No streams found"). They never take focus. */
@Stable
class TransientMessages {
    var current by mutableStateOf<TransientMessage?>(null)
        private set
    private var nextId = 0L

    fun show(text: String) {
        current = TransientMessage(text, nextId++)
    }

    fun dismiss(message: TransientMessage) {
        if (current == message) current = null
    }
}

val LocalTransientMessages = staticCompositionLocalOf<TransientMessages?> { null }

/** Bottom-centre pill showing [messages] for [TRANSIENT_MESSAGE_MILLIS] each. Not focusable. */
@Composable
fun TransientMessageHost(messages: TransientMessages) {
    val message = messages.current
    var shown by remember { mutableStateOf(message?.text.orEmpty()) }
    if (message != null) shown = message.text
    LaunchedEffect(message) {
        if (message == null) return@LaunchedEffect
        delay(TRANSIENT_MESSAGE_MILLIS)
        messages.dismiss(message)
    }
    Box(Modifier.fillMaxSize().padding(bottom = 40.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = message != null,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
        ) {
            Text(
                shown,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = OnFocusPill,
                modifier = Modifier
                    .background(FocusPill, RoundedCornerShape(50))
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
    }
}

const val TRANSIENT_MESSAGE_MILLIS = 2_500L
