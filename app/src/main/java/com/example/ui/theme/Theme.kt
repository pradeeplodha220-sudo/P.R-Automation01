package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val FullRedColorScheme = darkColorScheme(
    primary = RedPrimary,
    onPrimary = RedOnPrimary,
    primaryContainer = RedPrimaryContainer,
    onPrimaryContainer = RedOnPrimaryContainer,

    secondary = RedSecondary,
    onSecondary = RedOnSecondary,
    secondaryContainer = RedSecondaryContainer,
    onSecondaryContainer = RedOnSecondaryContainer,

    tertiary = RedTertiary,
    onTertiary = RedOnTertiary,
    tertiaryContainer = RedTertiaryContainer,
    onTertiaryContainer = RedOnTertiaryContainer,

    background = RedBackground,
    onBackground = RedOnBackground,
    surface = RedSurface,
    onSurface = RedOnSurface,
    surfaceVariant = RedSurfaceVariant,
    onSurfaceVariant = RedOnSurfaceVariant,

    outline = RedOutline,
    outlineVariant = RedOutlineVariant,
    error = Color(0xFFFF453A),
    onError = Color.White
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false, // Forced false for user's explicit "Ye red karo full" styling
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = FullRedColorScheme,
        typography = Typography,
        content = content
    )
}
