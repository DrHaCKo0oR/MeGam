package app.shaqati.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Green = Color(0xFF1F6F5C)
private val GreenDark = Color(0xFF0F3D33)
private val GreenLight = Color(0xFF8ED1BC)
private val Sand = Color(0xFFF7F3EC)
private val Gold = Color(0xFFB8893B)

private val LightColors = lightColorScheme(
    primary = Green,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEDE2),
    onPrimaryContainer = GreenDark,
    secondary = Gold,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF4E4C4),
    onSecondaryContainer = Color(0xFF3D2C0B),
    tertiary = Color(0xFF3A5A8C),
    background = Sand,
    onBackground = Color(0xFF1B1C1A),
    surface = Sand,
    onSurface = Color(0xFF1B1C1A),
    surfaceVariant = Color(0xFFE9E3D8),
    onSurfaceVariant = Color(0xFF4B4740),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFCFAF6),
    surfaceContainer = Color(0xFFF2EEE6),
    surfaceContainerHigh = Color(0xFFECE7DE),
    surfaceContainerHighest = Color(0xFFE6E0D6),
    outline = Color(0xFF7D776D),
    outlineVariant = Color(0xFFD4CDC1),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = GreenLight,
    onPrimary = Color(0xFF00382C),
    primaryContainer = Color(0xFF15513F),
    onPrimaryContainer = Color(0xFFCDEDE2),
    secondary = Color(0xFFE7C17A),
    onSecondary = Color(0xFF412D00),
    secondaryContainer = Color(0xFF5C4413),
    onSecondaryContainer = Color(0xFFF4E4C4),
    tertiary = Color(0xFFA9C7FF),
    background = Color(0xFF111413),
    onBackground = Color(0xFFE2E3DF),
    surface = Color(0xFF111413),
    onSurface = Color(0xFFE2E3DF),
    surfaceVariant = Color(0xFF3F4945),
    onSurfaceVariant = Color(0xFFBFC9C3),
    surfaceContainerLowest = Color(0xFF0C0F0E),
    surfaceContainerLow = Color(0xFF191C1B),
    surfaceContainer = Color(0xFF1D201F),
    surfaceContainerHigh = Color(0xFF272B29),
    surfaceContainerHighest = Color(0xFF323634),
    outline = Color(0xFF89938E),
    outlineVariant = Color(0xFF3F4945),
)

/** Colours with meaning: verdicts and requirement checks. */
@Immutable
data class StatusColors(
    val good: Color,
    val goodContainer: Color,
    val warn: Color,
    val warnContainer: Color,
    val bad: Color,
    val badContainer: Color,
    val neutral: Color,
    val neutralContainer: Color,
)

private val LightStatus = StatusColors(
    good = Color(0xFF1B7F4B), goodContainer = Color(0xFFD5F2E1),
    warn = Color(0xFF9A6200), warnContainer = Color(0xFFFCE8C6),
    bad = Color(0xFFB3261E), badContainer = Color(0xFFF9DEDC),
    neutral = Color(0xFF5E5A52), neutralContainer = Color(0xFFE9E3D8),
)

private val DarkStatus = StatusColors(
    good = Color(0xFF7FD9A6), goodContainer = Color(0xFF0E3B25),
    warn = Color(0xFFF2C46B), warnContainer = Color(0xFF4A3200),
    bad = Color(0xFFF2B8B5), badContainer = Color(0xFF601410),
    neutral = Color(0xFFC9C4BA), neutralContainer = Color(0xFF34322E),
)

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val Base = Typography()

private val AppTypography = Base.copy(
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 21.sp),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = Base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

@Composable
fun ShaqatiTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}

object AppTheme {
    val status: StatusColors
        @Composable get() = LocalStatusColors.current
}
