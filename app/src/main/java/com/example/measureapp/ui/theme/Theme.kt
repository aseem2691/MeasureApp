package com.example.measureapp.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// iOS Measure-inspired color palette
private val IosYellow = Color(0xFFFFCC00)
private val IosGreen = Color(0xFF34C759)

private val DarkColorScheme = darkColorScheme(
    primary = IosYellow,
    onPrimary = Color.Black,
    secondary = IosGreen,
    onSecondary = Color.Black,
    tertiary = Color(0xFF0A84FF),       // iOS blue
    background = Color(0xFF000000),
    onBackground = Color.White,
    surface = Color(0xFF1C1C1E),         // iOS dark surface
    onSurface = Color.White,
    surfaceVariant = Color(0xFF2C2C2E),  // iOS elevated surface
    onSurfaceVariant = Color(0xFF8E8E93),// iOS secondary text
    error = Color(0xFFFF3B30),           // iOS red
    outline = Color(0xFF38383A)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFFE5A800),          // Slightly darker yellow for light bg
    onPrimary = Color.White,
    secondary = IosGreen,
    onSecondary = Color.White,
    tertiary = Color(0xFF007AFF),         // iOS blue
    background = Color(0xFFF2F2F7),       // iOS light background
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
    surfaceVariant = Color(0xFFE5E5EA),   // iOS grouped bg
    onSurfaceVariant = Color(0xFF636366), // iOS secondary text
    error = Color(0xFFFF3B30),
    outline = Color(0xFFC6C6C8)
)

@Composable
fun MeasureAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

