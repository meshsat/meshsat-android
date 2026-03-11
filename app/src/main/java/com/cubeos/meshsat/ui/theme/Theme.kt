package com.cubeos.meshsat.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val MeshSatColorScheme = darkColorScheme(
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

@Composable
fun MeshSatTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MeshSatColorScheme,
        typography = MeshSatTypography,
        content = content,
    )
}
