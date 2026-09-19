package net.meshsat.android.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The app is dark, as the brand uses by default and the Bridge does. The light theme was removed
 * (MESHSAT-1249): it was never saved and left dark cards under dark text. A night mode follows.
 */
object ThemeState {
    private val _darkMode = MutableStateFlow<Boolean?>(true)
    val darkMode: StateFlow<Boolean?> = _darkMode
}

private val MeshSatColorScheme = darkColorScheme(
    primary = SignalOrange,
    onPrimary = MeshSatInk,
    primaryContainer = Color(0xFF3D1405),
    onPrimaryContainer = Color(0xFFFFC4A6),
    secondary = ColorMesh,
    onSecondary = MeshSatInk,
    secondaryContainer = MeshSatSurfaceLight,
    onSecondaryContainer = MeshSatTextPrimary,
    tertiary = ColorIridium,
    onTertiary = MeshSatInk,
    background = MeshSatBg,
    onBackground = MeshSatTextPrimary,
    surface = MeshSatSurface,
    onSurface = MeshSatTextPrimary,
    surfaceVariant = MeshSatSurfaceLight,
    onSurfaceVariant = MeshSatTextSecondary,
    surfaceTint = Color.Transparent,
    surfaceDim = MeshSatBg,
    surfaceBright = MeshSatSurfaceLight,
    surfaceContainerLowest = MeshSatBg,
    surfaceContainerLow = Color(0xFF0B0B0F),
    surfaceContainer = MeshSatSurface,
    surfaceContainerHigh = Color(0xFF1B1B22),
    surfaceContainerHighest = MeshSatSurfaceLight,
    inverseSurface = OffWhite,
    inverseOnSurface = MeshSatInk,
    inversePrimary = Color(0xFFBF450B),
    outline = Color(0xFF3A3A44),
    outlineVariant = MeshSatBorder,
    error = MeshSatRed,
    onError = MeshSatInk,
    errorContainer = Color(0xFF3B1414),
    onErrorContainer = Color(0xFFFCA5A5),
    scrim = Color(0xB3000000),
)

/** The Bridge's radii: 4 for controls, 8 for cards, 12 for sheets and dialogs. */
val MeshSatShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

@Composable
fun MeshSatTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MeshSatColorScheme,
        typography = MeshSatTypography,
        shapes = MeshSatShapes,
        content = content,
    )
}
