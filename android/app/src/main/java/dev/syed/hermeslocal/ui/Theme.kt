package dev.syed.hermeslocal.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val FallbackDark = darkColorScheme(
    primary = Color(0xFF4DD0A1), onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF00513D), onPrimaryContainer = Color(0xFF6FEDBB),
    secondary = Color(0xFFB2CCC0), tertiary = Color(0xFFFFB95C),
)
private val FallbackLight = lightColorScheme(
    primary = Color(0xFF006C52), primaryContainer = Color(0xFF6FEDBB), tertiary = Color(0xFF855400),
)

/** AMOLED: true black surfaces, containers lifted just enough to read as layers. */
private fun ColorScheme.amoled() = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0B0D0E),
    surfaceContainer = Color(0xFF121416),
    surfaceContainerHigh = Color(0xFF1A1C1E),
    surfaceContainerHighest = Color(0xFF232628),
)

@Composable
fun HermesTheme(amoled: Boolean, dynamic: Boolean, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val base = when {
        dynamic && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> FallbackDark
        else -> FallbackLight
    }
    val scheme = if (dark && amoled) base.amoled() else base
    MaterialExpressiveTheme(colorScheme = scheme, motionScheme = MotionScheme.expressive(), content = content)
}

/** Status colours shared by the lever, the chip and the notification copy. */
object StatusColors {
    val Amber = Color(0xFFFFB23F)
    val Red = Color(0xFFFF5A52)
    val Green = Color(0xFF3DDC97)
}
