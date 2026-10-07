package br.com.leitorpdf.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ListenOrange = Color(0xFFFF5A1F)
private val ListenPeach = Color(0xFFFFF3ED)
private val ListenInk = Color(0xFF171717)
private val ListenMuted = Color(0xFF6F6F6F)

private val LightColors = lightColorScheme(
    primary = ListenOrange,
    onPrimary = Color.White,
    secondary = Color(0xFFFF8A5B),
    background = ListenPeach,
    surface = Color.White,
    onBackground = ListenInk,
    onSurface = ListenInk,
    onSurfaceVariant = ListenMuted
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF7040),
    secondary = Color(0xFFFFA07A)
)

@Composable
fun LeitorPdfTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = androidx.compose.material3.Typography(),
        content = content
    )
}
