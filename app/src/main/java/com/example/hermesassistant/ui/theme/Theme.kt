package com.example.hermesassistant.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Mirrors the legacy palette (#0D0F14 page, #1E293B surfaces,
// #34D399 accent) so the Compose surface feels like the same app.
private val HermesDarkColors = darkColorScheme(
    primary = Color(0xFF34D399),
    onPrimary = Color(0xFF0D0F14),
    secondary = Color(0xFF60A5FA),
    onSecondary = Color(0xFF0D0F14),
    background = Color(0xFF0D0F14),
    onBackground = Color(0xFFE5E7EB),
    surface = Color(0xFF12151D),
    onSurface = Color(0xFFE5E7EB),
    surfaceVariant = Color(0xFF1E293B),
    onSurfaceVariant = Color(0xFF8B93A7),
    outline = Color(0xFF334155),
    error = Color(0xFFF87171),
)

private val HermesShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun HermesTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = HermesDarkColors,
        shapes = HermesShapes,
        content = content,
    )
}
