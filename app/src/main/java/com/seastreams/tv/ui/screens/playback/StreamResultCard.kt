package com.seastreams.tv.ui.screens.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.seastreams.tv.domain.model.StreamSource
import com.seastreams.tv.ui.theme.LastUsedGold
import com.seastreams.tv.ui.theme.Obsidian
import com.seastreams.tv.ui.theme.focusPillColors
import com.seastreams.tv.ui.theme.ignoreInitialKeyUp

/**
 * The full-width focusable pill every stream panel entry sits in (result cards and the panel's
 * status rows). Long-pressing Play opens the panel while OK is still held, so a held `OK` that
 * started before the entry was focused never fires it ([ignoreInitialKeyUp]).
 */
@Composable
internal fun StreamPanelEntry(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.ignoreInitialKeyUp().fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = focusPillColors(containerColor = Color.White.copy(alpha = 0.08f)),
        content = content,
    )
}

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
 * follows its content. [selected] marks the playing stream with a check beside the addon name;
 * [lastUsed] adds the gold `LAST USED` pill at the top-right, on the addon-name row (`last_used.png`).
 * A held `OK` that started before the card was focused never chooses it ([ignoreInitialKeyUp]).
 */
@Composable
fun StreamResultCard(
    stream: StreamSource,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    rank: Int? = null,
    selected: Boolean = false,
    lastUsed: Boolean = false,
) {
    val text = streamCardText(stream, rank)
    StreamPanelEntry(onClick = onClick, modifier = modifier) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (selected) Icon(Icons.Filled.Check, contentDescription = "Playing", modifier = Modifier.size(16.dp))
                // weight(1f): a long addon name wraps beside the badge instead of running under it.
                Text(
                    text.header,
                    style = MaterialTheme.typography.labelMedium,
                    color = LocalContentColor.current.copy(alpha = 0.7f),
                    modifier = Modifier.weight(1f),
                )
                if (lastUsed) LastUsedBadge()
            }
            Text(text.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            text.detailLines.forEach { line ->
                Text(line, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Fixed Obsidian-on-gold colours, so it reads the same on the translucent and the `#E5E9F0` focused card. */
@Composable
private fun LastUsedBadge() {
    Text(
        LAST_USED_LABEL,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.6.sp,
        color = Obsidian,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .background(LastUsedGold, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

internal const val LAST_USED_LABEL = "LAST USED"
