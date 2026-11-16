package com.seastreams.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.seastreams.tv.domain.model.ProfileAvatar

/** Non-interactive avatar artwork; wrap it in a focusable Surface where it must be clickable. */
@Composable
fun AvatarTile(avatarIndex: Int, size: Dp, modifier: Modifier = Modifier) {
    val avatar = ProfileAvatar.at(avatarIndex)
    Box(
        modifier
            .size(size)
            .background(Color(avatar.argb), RoundedCornerShape(size * 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(avatar.glyph, fontSize = (size.value * 0.5f).sp)
    }
}
