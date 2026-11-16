package com.seastreams.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.seastreams.tv.ui.theme.SurfaceCard
import com.seastreams.tv.ui.theme.TextSecondary

/** Non-focusable skeleton for a catalog row that is still loading. */
@Composable
fun LoadingRow(title: String) {
    Column {
        RowTitle(title)
        Row(Modifier.padding(start = RowGutter), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(PLACEHOLDER_CARDS) {
                Box(Modifier.size(PosterWidth, CardHeight).clip(RoundedCornerShape(12.dp)).background(SurfaceCard))
            }
        }
    }
}

/** Non-focusable notice for a catalog row whose source couldn't be fetched. */
@Composable
fun FailedRow(title: String) {
    Column {
        RowTitle(title)
        Text(
            "Couldn't load this row. Check your connection and reopen this tab.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            modifier = Modifier.padding(start = RowGutter),
        )
    }
}

private const val PLACEHOLDER_CARDS = 7
