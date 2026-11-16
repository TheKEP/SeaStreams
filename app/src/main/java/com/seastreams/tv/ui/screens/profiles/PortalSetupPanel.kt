package com.seastreams.tv.ui.screens.profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.seastreams.tv.ui.components.QrCode
import com.seastreams.tv.ui.components.TvTextField
import com.seastreams.tv.ui.theme.ErrorRed
import com.seastreams.tv.ui.theme.SuccessGreen
import com.seastreams.tv.ui.theme.TextSecondary

/**
 * Right-hand panel: live QR code + LAN URL for the Web Portal, the TMDB key status (which flips
 * reactively when the key is saved from a phone), and on-TV key entry as a fallback.
 */
@Composable
fun PortalSetupPanel(
    state: ProfileCreateUiState,
    onKeyInputChange: (String) -> Unit,
    onVerifyKey: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Set up from your phone", style = MaterialTheme.typography.titleMedium)
        val url = state.portal.url
        if (url != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                QrCode(text = url, modifier = Modifier.size(150.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Scan the code or open", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    Text(url, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "on a phone or computer on the same Wi-Fi to enter your TMDB key, profiles and addons.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                }
            }
        } else {
            Text(state.portal.problem.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        }

        KeyStatus(state)

        // Always composed and always enabled: removing or disabling the focused control would drop D-pad focus.
        TvTextField(
            value = state.form.keyInput,
            onValueChange = onKeyInputChange,
            label = if (state.keySaved) "Replace TMDB API key" else "Or type your TMDB API key here",
            placeholder = "TMDB API key or read access token",
            keyboardType = KeyboardType.Ascii,
            onDone = onVerifyKey,
        )
        Button(onClick = onVerifyKey) {
            Text(if (state.form.validatingKey) "Verifying…" else "Verify key")
        }
        state.form.keyError?.let { Text(it, color = ErrorRed, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun KeyStatus(state: ProfileCreateUiState) {
    val (text, color) = if (state.keySaved) {
        "✓ TMDB key verified ${state.maskedKey.orEmpty()}" to SuccessGreen
    } else {
        "Waiting for a valid TMDB API key…" to TextSecondary
    }
    Text(text, color = color, style = MaterialTheme.typography.titleSmall)
}
