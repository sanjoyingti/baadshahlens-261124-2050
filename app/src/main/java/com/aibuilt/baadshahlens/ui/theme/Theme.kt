package com.aibuilt.baadshahlens.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkScheme = darkColorScheme(
    primary = Gold,
    onPrimary = Ink,
    primaryContainer = GoldDeep,
    onPrimaryContainer = Ink,
    secondary = AmberSoft,
    onSecondary = Ink,
    background = Ink,
    onBackground = Mist,
    surface = InkElevated,
    onSurface = Mist,
    surfaceVariant = InkCard,
    onSurfaceVariant = MistDim,
    error = Danger,
    onError = Color.White,
    outline = Color(0xFF3A3A42)
)

private val LightScheme = lightColorScheme(
    primary = GoldDeep,
    onPrimary = Color.White,
    primaryContainer = AmberSoft,
    onPrimaryContainer = Ink,
    secondary = GoldDeep,
    onSecondary = Color.White,
    background = Color(0xFFF7F5F0),
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFEDEAE2),
    onSurfaceVariant = Color(0xFF55524C),
    error = Danger,
    onError = Color.White,
    outline = Color(0xFFCFCAC0)
)

@Composable
fun BaadshahTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = BaadshahTypography,
        content = content
    )
}
