package dev.syed.hermeslocal.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.android.material.color.utilities.Hct
import com.google.android.material.color.utilities.MaterialDynamicColors
import com.google.android.material.color.utilities.SchemeTonalSpot
import dev.syed.hermeslocal.AppController
import dev.syed.hermeslocal.engine.EngineStatus
import dev.syed.hermeslocal.engine.Phase
import dev.syed.hermeslocal.engine.SettingsSnapshot
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * test6 theming check: every screen under Material You schemes made from three wallpaper seeds (tonal spot, the
 * Android 12+ default), plus the app's own scheme (dynamic colour off), each in AMOLED and non-AMOLED.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "w411dp-h891dp-night-xxhdpi")
class ThemeScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val out = System.getProperty("shots.dir") ?: "build/shots"
    private val ready = SettingsSnapshot(passwordSet = true)

    @Before fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        app.applicationInfo.nativeLibraryDir = app.cacheDir.path
        AppController.init(app)
    }

    /** What dynamicDarkColorScheme gives for a wallpaper with this seed (tonal spot). */
    private fun seedScheme(seed: Int): ColorScheme {
        val s = SchemeTonalSpot(Hct.fromInt(seed), true, 0.0)
        val m = MaterialDynamicColors()
        fun c(dc: com.google.android.material.color.utilities.DynamicColor) = Color(dc.getArgb(s))
        return darkColorScheme(
            primary = c(m.primary()), onPrimary = c(m.onPrimary()), primaryContainer = c(m.primaryContainer()), onPrimaryContainer = c(m.onPrimaryContainer()),
            inversePrimary = c(m.inversePrimary()),
            secondary = c(m.secondary()), onSecondary = c(m.onSecondary()), secondaryContainer = c(m.secondaryContainer()), onSecondaryContainer = c(m.onSecondaryContainer()),
            tertiary = c(m.tertiary()), onTertiary = c(m.onTertiary()), tertiaryContainer = c(m.tertiaryContainer()), onTertiaryContainer = c(m.onTertiaryContainer()),
            error = c(m.error()), onError = c(m.onError()), errorContainer = c(m.errorContainer()), onErrorContainer = c(m.onErrorContainer()),
            background = c(m.background()), onBackground = c(m.onBackground()), surface = c(m.surface()), onSurface = c(m.onSurface()),
            surfaceVariant = c(m.surfaceVariant()), onSurfaceVariant = c(m.onSurfaceVariant()),
            surfaceDim = c(m.surfaceDim()), surfaceBright = c(m.surfaceBright()),
            surfaceContainerLowest = c(m.surfaceContainerLowest()), surfaceContainerLow = c(m.surfaceContainerLow()),
            surfaceContainer = c(m.surfaceContainer()), surfaceContainerHigh = c(m.surfaceContainerHigh()), surfaceContainerHighest = c(m.surfaceContainerHighest()),
            outline = c(m.outline()), outlineVariant = c(m.outlineVariant()),
            inverseSurface = c(m.inverseSurface()), inverseOnSurface = c(m.inverseOnSurface()),
        )
    }

    private val schemes: List<Triple<String, ColorScheme, Boolean>> by lazy {
        listOf(
            Triple("default", defaultScheme(), false),
            Triple("blue", seedScheme(0xFF1E6FD9.toInt()), true),
            Triple("orange", seedScheme(0xFFE0711A.toInt()), true),
            Triple("pink", seedScheme(0xFFC2185B.toInt()), true),
        )
    }

    @Test fun mainRunning() = one("main-running") {
        MainScreen(EngineStatus(phase = Phase.Running, runningSince = System.currentTimeMillis() - 1_126_000, awake = "Working", thermal = 2), ready) {}
    }
    @Test fun mainStarting() = one("main-starting") { MainScreen(EngineStatus(phase = Phase.Starting), ready) {} }
    @Test fun mainFailed() = one("main-failed") { MainScreen(EngineStatus(phase = Phase.Failed, message = "Hermes stopped (exit 1)"), ready) {} }
    @Test fun mainOff() = one("main-off") { MainScreen(EngineStatus(phase = Phase.Stopped), ready) {} }
    @Test fun settings() = one("settings") { SettingsScreen(EngineStatus(phase = Phase.Stopped), ready, onBack = {}, onNavigate = {}) }
    @Test fun storage() = one("storage", ms = 4000) { StorageScreen(EngineStatus(phase = Phase.Stopped), onBack = {}) }
    @Test fun providers() = one("providers", ms = 3000) { ProvidersScreen(EngineStatus(phase = Phase.Stopped), onBack = {}) }
    @Test fun logs() = one("logs") { LogsScreen(onBack = {}) }

    private fun one(screen: String, ms: Long = 1500, content: @Composable () -> Unit) {
        val which = System.getProperty("theme.only") // optional filter while iterating
        if (which != null && which != screen) return
        shotsEach(screen, ms, content)
    }

    /** setContent may be called once per rule: render every scheme as one content that switches by state. */
    private fun shotsEach(screen: String, ms: Long, content: @Composable () -> Unit) {
        val combos = schemes.flatMap { (n, b, d) -> listOf(false, true).map { a -> Triple("$n-${if (a) "amoled" else "dark"}", if (a) amoledOf(b) else b, d) } }
        var idx by mutableIntStateOf(0)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val (_, scheme, dynamic) = combos[idx]
            key(idx) {
                HermesTheme(scheme, harmonize = dynamic) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) { content() }
                }
            }
        }
        combos.forEachIndexed { i, (name, _, _) ->
            compose.runOnUiThread { idx = i }
            Snapshot.sendApplyNotifications() // static screens schedule no frame of their own: push the switch to the recomposer
            compose.mainClock.advanceTimeBy(32)
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(ms)
            compose.onRoot().captureRoboImage("$out/t6-$screen-$name.png")
        }
    }
}
