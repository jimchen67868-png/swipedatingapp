package com.example.swipe.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Ink = Color(0xFF14161A)
val Page = Color(0xFFF2F4F7)
val Yellow = Color(0xFFFFD23F)
val LikeGreen = Color(0xFF1FB57A)
val NopeRed = Color(0xFFE5484D)

private val Colors = lightColorScheme(
    primary = Ink,
    onPrimary = Color.White,
    secondary = Yellow,
    onSecondary = Ink,
    background = Page,
    surface = Color.White,
    onSurface = Ink
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
