package com.seastreams.tv.ui.screens.playback

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.seastreams.tv.domain.model.StreamSource
import com.seastreams.tv.ui.theme.focusPillColors
import com.seastreams.tv.ui.theme.ignoreInitialKeyUp

/** The text a stream result card shows: nothing is shortened, merged or dropped. */
internal data class StreamCardText(val header: String, val title: String, val detailLines: List<String>)

/**
 * Splits an addon stream into card text. Every non-blank description line is kept as its own line
 * (trailing whitespace trimmed) so a long filename that wraps never pushes the audio/size line out.
 */
internal fun streamCardText(stream: StreamSource, rank: Int? = null): StreamCardText = StreamCardText(
    header = stream.addonName,
    title = if (rank == null) stream.label else "#$rank  ${stream.label}",
    detailLines = stream.description.lines().map { it.trimEnd() }.filter { it.isNotBlank() },
)

/**
 * A stream result pill shared by the Details stream selection modal and the in-player `Streams`
 * overlay. It spans the full list width, its text soft-wraps with no line caps, and its height
 * follows its content. [selected] marks the playing stream with a check beside the addon name.
 * A held `OK` that started before the card was focused never chooses it ([ignoreInitialKeyUp]).
 */
@Composable
fun StreamResultCard(
    stream: StreamSource,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    rank: Int? = null,
    selected: Boolean = false,
) {
    val text = streamCardText(stream, rank)
    Surface(
        onClick = onClick,
        modifier = modifier.ignoreInitialKeyUp().fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = focusPillColors(containerColor = Color.White.copy(alpha = 0.08f)),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (selected) Icon(Icons.Filled.Check, contentDescription = "Playing", modifier = Modifier.size(16.dp))
                Text(text.header, style = MaterialTheme.typography.labelMedium, color = LocalContentColor.current.copy(alpha = 0.7f))
            }
            Text(text.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            text.detailLines.forEach { line ->
                Text(line, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
