package com.cubeos.meshsat.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Global theme mode: null = follow system, true = dark, false = light. Default: dark (true). */
object ThemeState {
    private val _darkMode = MutableStateFlow<Boolean?>(true)
    val darkMode: StateFlow<Boolean?> = _darkMode

    fun setDarkMode(dark: Boolean?) { _darkMode.value = dark }
}

private val DarkColorScheme = darkColorScheme(
    primary = MeshSatTeal,
    onPrimary = MeshSatTextPrimary,
    secondary = MeshSatTealLight,
    onSecondary = MeshSatTextPrimary,
    tertiary = MeshSatAmber,
    background = MeshSatBg,
    onBackground = MeshSatTextPrimary,
    surface = MeshSatSurface,
    onSurface = MeshSatTextPrimary,
    surfaceVariant = MeshSatSurfaceLight,
    onSurfaceVariant = MeshSatTextSecondary,
    outline = MeshSatBorder,
    error = MeshSatRed,
    onError = MeshSatTextPrimary,
)

private val LightColorScheme = lightColorScheme(
    primary = MeshSatTeal,
    onPrimary = Color.White,
    secondary = MeshSatTealLight,
    onSecondary = Color.White,
    tertiary = MeshSatAmber,
    background = MeshSatBgLight,
    onBackground = MeshSatTextPrimaryLight,
    surface = MeshSatSurfaceLight2,
    onSurface = MeshSatTextPrimaryLight,
    surfaceVariant = MeshSatSurfaceLightAlt,
    onSurfaceVariant = MeshSatTextSecondaryLight,
    outline = MeshSatBorderLight,
    error = MeshSatRed,
    onError = Color.White,
)

@Composable
fun MeshSatTheme(content: @Composable () -> Unit) {
    val darkModePref by ThemeState.darkMode.collectAsState()
    val isDark = darkModePref ?: isSystemInDarkTheme()

    MaterialTheme(
        colorScheme = if (isDark) DarkColorScheme else LightColorScheme,
        typography = MeshSatTypography,
        content = content,
    )
}
