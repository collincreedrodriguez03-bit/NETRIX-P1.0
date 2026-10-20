package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val NetrixColorScheme = darkColorScheme(
    primary = NetrixPrimary,
    onPrimary = NetrixVoid,
    secondary = NetrixSecondary,
    onSecondary = NetrixText,
    background = NetrixVoid,
    onBackground = NetrixText,
    surface = NetrixSurface,
    onSurface = NetrixText,
    surfaceVariant = NetrixSurfaceSubtle,
    onSurfaceVariant = NetrixTextDim,
    outline = NetrixBorder,
    error = NetrixRed,
    onError = NetrixVoid
)

@Composable
fun NetrixTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = NetrixColorScheme,
        typography = Typography,
        content = content
    )
}
