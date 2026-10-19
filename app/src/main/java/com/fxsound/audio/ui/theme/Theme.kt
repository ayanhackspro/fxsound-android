package com.fxsound.audio.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val FxSoundColorScheme = darkColorScheme(
    primary = FxRedPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2B1218),
    onPrimaryContainer = TextPrimary,
    secondary = FxRedLight,
    onSecondary = Color.White,
    background = DarkBackground,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    error = DangerRed,
    onError = Color.White
)

@Composable
fun FxSoundTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = FxSoundColorScheme,
        typography = FxSoundTypography,
        content = content
    )
}
