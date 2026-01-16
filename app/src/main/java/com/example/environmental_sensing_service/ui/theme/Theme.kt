package com.example.environmental_sensing_service.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

private val LightColorScheme = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = AccentSoft,
    onPrimaryContainer = Ink,
    secondary = InkMuted,
    onSecondary = Color.White,
    background = Cloud,
    onBackground = Ink,
    surface = Surface,
    onSurface = Ink,
    surfaceVariant = Color(0xFFF1F3F7),
    onSurfaceVariant = InkMuted,
    outline = Border
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp)
)

@Composable
fun EnvironmentalSensingServiceTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = Accent,
            onPrimary = Color.White,
            primaryContainer = Color(0xFF1B2550),
            onPrimaryContainer = Color.White,
            secondary = InkMuted,
            onSecondary = Color.White,
            background = Color(0xFF0E1116),
            onBackground = Color(0xFFF8FAFF),
            surface = Color(0xFF161B22),
            onSurface = Color(0xFFF8FAFF),
            surfaceVariant = Color(0xFF1D2330),
            onSurfaceVariant = Color(0xFFB4BCCB),
            outline = Color(0xFF2D3544)
        )
    } else {
        LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = AppShapes,
        typography = Typography,
        content = content
    )
}
