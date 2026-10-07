package com.btcusd.alerts.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

private val Scheme = darkColorScheme(
    background = Color(0xFF0B0E11),
    surface = Color(0xFF14181D),
    primary = Color(0xFFF7A600),
    onPrimary = Color.Black,
    onBackground = Color(0xFFF2F2F0),
    onSurface = Color(0xFFF2F2F0)
)

/** Premium terminal theme: quiet chrome, tabular numerals, 400/500 only. */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = MaterialTheme.typography, content = content)
}
