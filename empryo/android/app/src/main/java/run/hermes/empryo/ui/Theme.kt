package run.hermes.empryo.ui

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** The dark lavender look of Syed's reference (the default, and the fallback below Android 12). */
private val Scheme = darkColorScheme(
    primary = Color(0xFFD7BFFA), onPrimary = Color(0xFF2B1650),
    primaryContainer = Color(0xFF3E2E5C), onPrimaryContainer = Color(0xFFEBDDFF),
    secondary = Color(0xFFCDC2DB), secondaryContainer = Color(0xFF332D40), onSecondaryContainer = Color(0xFFE5DCF2),
    tertiary = Color(0xFF8FD99A),
    background = Color(0xFF0E0D11), onBackground = Color(0xFFE7E1EA),
    surface = Color(0xFF0E0D11), onSurface = Color(0xFFE7E1EA), onSurfaceVariant = Color(0xFFB4ADBC),
    surfaceContainerLowest = Color(0xFF0A090C), surfaceContainerLow = Color(0xFF151419),
    surfaceContainer = Color(0xFF1C1B21), surfaceContainerHigh = Color(0xFF26252C), surfaceContainerHighest = Color(0xFF302F37),
    outline = Color(0xFF8E8796), outlineVariant = Color(0xFF3C3A43),
)

/**
 * [amoled]: pure black background and surface; containers stay a few steps above black so cards remain visible.
 * [dynamic]: wallpaper colours (Material You) on Android 12+. Status colours (lever, dot, blob) never change.
 */
fun colorScheme(context: Context, amoled: Boolean, dynamic: Boolean): ColorScheme {
    val base = if (dynamic && Build.VERSION.SDK_INT >= 31) runCatching { dynamicDarkColorScheme(context) }.getOrDefault(Scheme) else Scheme
    if (!amoled) return base
    return base.copy(
        background = Color.Black, surface = Color.Black, surfaceDim = Color.Black, surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color(0xFF0B0B0D), surfaceContainer = Color(0xFF131216),
        surfaceContainerHigh = Color(0xFF1B1A1F), surfaceContainerHighest = Color(0xFF25242A),
    )
}

@Composable
fun EmpryoTheme(amoled: Boolean = false, dynamic: Boolean = false, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme = remember(amoled, dynamic) { colorScheme(ctx, amoled, dynamic) }
    MaterialExpressiveTheme(colorScheme = scheme, motionScheme = MotionScheme.expressive(), content = content)
}

/** Status colours shared by the lever, the dot, the chips and the blob. */
object StatusColors {
    val Green = Color(0xFF8FD99A)
    val GreenBlob = Color(0xFF233B28)
    val Amber = Color(0xFFFFB95C)
    val Red = Color(0xFFFF6B63)
}
