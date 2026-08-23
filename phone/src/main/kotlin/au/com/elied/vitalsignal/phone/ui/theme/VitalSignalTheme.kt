package au.com.elied.vitalsignal.phone.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

val Ink = Color(0xFF041112)
val SurfaceDeep = Color(0xFF091A1C)
val SurfaceLifted = Color(0xFF0E2628)
val SurfaceBright = Color(0xFF173638)
val Mint = Color(0xFF72E8C8)
val MintSoft = Color(0xFFB0F5E1)
val Ice = Color(0xFFE8FBF6)
val Slate = Color(0xFFA9BFBD)
val Quiet = Color(0xFF839D9B)
val Amber = Color(0xFFFFCB72)
val Rose = Color(0xFFFF929E)
val Blue = Color(0xFF91CFFF)
val Violet = Color(0xFFB9B0FF)

private val VitalDarkColors = darkColorScheme(
    primary = Mint,
    onPrimary = Ink,
    primaryContainer = Color(0xFF173E3B),
    onPrimaryContainer = Ice,
    secondary = Blue,
    onSecondary = Ink,
    tertiary = Amber,
    background = Ink,
    onBackground = Ice,
    surface = SurfaceDeep,
    onSurface = Ice,
    surfaceVariant = SurfaceLifted,
    onSurfaceVariant = Slate,
    outline = Color(0xFF2E5052),
    error = Rose,
)

private val VitalTypography = androidx.compose.material3.Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 34.sp,
        lineHeight = 38.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.7).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 28.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.45).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 22.sp,
        lineHeight = 27.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.25).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Normal,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        lineHeight = 21.sp,
        fontWeight = FontWeight.Normal,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.2.sp,
    ),
)

@Composable
fun VitalSignalTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            if (Build.VERSION.SDK_INT < 35) {
                @Suppress("DEPRECATION")
                run {
                    window.statusBarColor = Color.Transparent.toArgb()
                    window.navigationBarColor = Ink.toArgb()
                }
            }
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = VitalDarkColors,
        typography = VitalTypography,
        content = content,
    )
}
