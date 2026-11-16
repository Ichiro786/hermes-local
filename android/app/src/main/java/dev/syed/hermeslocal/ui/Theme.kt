package dev.syed.hermeslocal.ui

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import kotlin.math.abs
import kotlin.math.min

/**
 * The dark lavender look of Syed's reference (dynamic colour off, and the fallback below Android 12).
 * Its tertiary is the green "running" colour and its error the red "failed" colour, so the status colours
 * below come from the scheme in every mode and follow Material You when dynamic colour is on.
 */
private val Scheme = darkColorScheme(
    primary = Color(0xFFD7BFFA), onPrimary = Color(0xFF2B1650),
    primaryContainer = Color(0xFF3E2E5C), onPrimaryContainer = Color(0xFFEBDDFF), inversePrimary = Color(0xFF6E5A9C),
    secondary = Color(0xFFCDC2DB), secondaryContainer = Color(0xFF332D40), onSecondaryContainer = Color(0xFFE5DCF2),
    tertiary = Color(0xFF8FD99A), onTertiary = Color(0xFF00391A),
    tertiaryContainer = Color(0xFF233B28), onTertiaryContainer = Color(0xFFAAF5B4),
    error = Color(0xFFFF6B63), errorContainer = Color(0xFF3D2427), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0E0D11), onBackground = Color(0xFFE7E1EA),
    surface = Color(0xFF0E0D11), onSurface = Color(0xFFE7E1EA), onSurfaceVariant = Color(0xFFB4ADBC),
    surfaceContainerLowest = Color(0xFF0A090C), surfaceContainerLow = Color(0xFF151419),
    surfaceContainer = Color(0xFF1C1B21), surfaceContainerHigh = Color(0xFF26252C), surfaceContainerHighest = Color(0xFF302F37),
    outline = Color(0xFF8E8796), outlineVariant = Color(0xFF3C3A43),
)

/** "Busy" (starting, stopping, setting up) has no M3 role: an amber that leans toward the primary hue under dynamic colour. */
private val Amber = Color(0xFFFFB95C)

/**
 * [amoled]: pure black background and surface; containers stay a few steps above black so cards remain visible
 * (they keep the wallpaper's tint under dynamic colour).
 * [dynamic]: wallpaper colours (Material You) on Android 12+.
 */
fun colorScheme(context: Context, amoled: Boolean, dynamic: Boolean): ColorScheme {
    val base = if (dynamic && Build.VERSION.SDK_INT >= 31) runCatching { dynamicDarkColorScheme(context) }.getOrDefault(Scheme) else Scheme
    return if (amoled) amoledOf(base) else base
}

/** The default (non-dynamic) scheme, for previews. */
fun defaultScheme(): ColorScheme = Scheme

fun amoledOf(base: ColorScheme): ColorScheme = base.copy(
    background = Color.Black, surface = Color.Black, surfaceDim = Color.Black, surfaceContainerLowest = Color.Black,
    surfaceContainerLow = dim(base.surfaceContainerLow, 0.52f), surfaceContainer = dim(base.surfaceContainer, 0.68f),
    surfaceContainerHigh = dim(base.surfaceContainerHigh, 0.71f), surfaceContainerHighest = dim(base.surfaceContainerHighest, 0.77f),
)

/** Darker in sRGB, same hue: keeps the wallpaper's tint on AMOLED containers. */
private fun dim(c: Color, k: Float) = Color(c.red * k, c.green * k, c.blue * k, c.alpha)

/**
 * Engine status colours, all derived from the active [ColorScheme]: running = tertiary roles, failed = error roles,
 * busy = amber harmonised toward the primary hue (unchanged with dynamic colour off).
 */
@Immutable
data class StatusPalette(
    val on: Color, val onBlob: Color, val onCard: Color,
    val busy: Color, val busyBlob: Color,
    val failed: Color, val failedBlob: Color,
)

fun statusPalette(s: ColorScheme, harmonize: Boolean): StatusPalette {
    val busy = if (harmonize) harmonized(Amber, s.primary) else Amber
    return StatusPalette(
        on = s.tertiary, onBlob = s.tertiaryContainer, onCard = lerp(s.surfaceContainer, s.tertiaryContainer, 0.5f),
        busy = busy, busyBlob = lerp(s.surfaceContainerHigh, busy, 0.14f),
        failed = s.error, failedBlob = lerp(s.surfaceContainerHigh, s.error, 0.16f),
    )
}

/** Material's harmonise: turn [c]'s hue toward [toward]'s by half the difference, at most 15°. */
private fun harmonized(c: Color, toward: Color): Color {
    val a = FloatArray(3).also { android.graphics.Color.colorToHSV(c.toArgb(), it) }
    val b = FloatArray(3).also { android.graphics.Color.colorToHSV(toward.toArgb(), it) }
    if (b[1] < 0.08f) return c // a near-grey primary has no hue to lean toward
    val diff = ((b[0] - a[0] + 540f) % 360f) - 180f
    a[0] = (a[0] + Math.copySign(min(abs(diff) * 0.5f, 15f), diff) + 360f) % 360f
    return Color(android.graphics.Color.HSVToColor(a))
}

private val LocalStatus = staticCompositionLocalOf { statusPalette(Scheme, harmonize = false) }

/** The status colours of the current theme. */
val MaterialTheme.status: StatusPalette
    @Composable @ReadOnlyComposable get() = LocalStatus.current

@Composable
fun HermesTheme(amoled: Boolean, dynamic: Boolean, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme = remember(amoled, dynamic) { colorScheme(ctx, amoled, dynamic) }
    HermesTheme(scheme, harmonize = dynamic && Build.VERSION.SDK_INT >= 31, content)
}

@Composable
fun HermesTheme(scheme: ColorScheme, harmonize: Boolean, content: @Composable () -> Unit) {
    val status = remember(scheme, harmonize) { statusPalette(scheme, harmonize) }
    CompositionLocalProvider(LocalStatus provides status) {
        MaterialExpressiveTheme(colorScheme = scheme, motionScheme = MotionScheme.expressive(), content = content)
    }
}
