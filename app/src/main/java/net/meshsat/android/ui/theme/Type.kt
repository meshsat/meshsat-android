package net.meshsat.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import net.meshsat.android.R

// IBM Plex, the Bridge's typefaces (MESHSAT-1249). Plex Sans stops at 600 (SemiBold): a Bold request
// maps to the SemiBold file rather than a synthesised fake bold, the Bridge's rule too. Plex Mono is
// for numbers, times and identifiers, where tabular figures line up.
val PlexSans = FontFamily(
    Font(R.font.plex_sans_regular, FontWeight.Normal),
    Font(R.font.plex_sans_medium, FontWeight.Medium),
    Font(R.font.plex_sans_semibold, FontWeight.SemiBold),
    Font(R.font.plex_sans_semibold, FontWeight.Bold),
)

val PlexMono = FontFamily(
    Font(R.font.plex_mono_regular, FontWeight.Normal),
    Font(R.font.plex_mono_medium, FontWeight.Medium),
    Font(R.font.plex_mono_medium, FontWeight.SemiBold),
    Font(R.font.plex_mono_medium, FontWeight.Bold),
)

private fun sans(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = PlexSans, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp)

// A 1.25 scale on a 16 sp body, with 12 sp as the floor: nothing in the app is smaller. The old
// scale used 10 sp for 232 labels and made metrics larger than screen titles.
val MeshSatTypography = Typography(
    displaySmall = sans(32, 40, FontWeight.SemiBold),
    headlineLarge = sans(28, 34, FontWeight.SemiBold),
    headlineMedium = sans(22, 28, FontWeight.SemiBold),
    headlineSmall = sans(20, 26, FontWeight.SemiBold),
    titleLarge = sans(18, 24, FontWeight.SemiBold),
    titleMedium = sans(16, 22, FontWeight.Medium),
    titleSmall = sans(14, 20, FontWeight.Medium),
    bodyLarge = sans(16, 24),
    bodyMedium = sans(14, 20),
    bodySmall = sans(12, 16),
    labelLarge = sans(14, 20, FontWeight.Medium),
    // Material's navigation bar and chips label with labelMedium, so it is Plex Sans; code that
    // wants figures in Plex Mono says so with fontFamily = PlexMono.
    labelMedium = sans(12, 16, FontWeight.Medium),
    labelSmall = sans(12, 16, FontWeight.Medium),
)
