package dev.syed.hermeslocal

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.syed.hermeslocal.engine.EngineState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import dev.syed.hermeslocal.ui.HermesTheme
import dev.syed.hermeslocal.ui.LogsScreen
import dev.syed.hermeslocal.ui.MainScreen
import dev.syed.hermeslocal.ui.ProvidersScreen
import dev.syed.hermeslocal.ui.SettingsScreen

enum class Screen { Main, Settings, Providers, Logs }

class MainActivity : ComponentActivity() {
    companion object {
        /** CI hook target screen (see handleCiHooks). */
        val ciScreen = kotlinx.coroutines.flow.MutableStateFlow<Screen?>(null)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleCiHooks(intent)
    }

    /**
     * CI only (BuildConfig.CI_HOOKS): `am start -e ci_password X --ez ci_toggle true` sets up and flips the lever;
     * `-e ci_screen providers` opens a screen; `-e ci_guest '<bash>'` runs a command in the engine and logs its
     * output (tag HermesLocalCI, prefix "guest:").
     */
    private fun handleCiHooks(intent: android.content.Intent?) {
        if (!BuildConfig.CI_HOOKS || intent == null) return
        intent.getStringExtra("ci_screen")?.let { name -> Screen.entries.firstOrNull { it.name.equals(name, true) }?.let { ciScreen.value = it } }
        intent.getStringExtra("ci_guest")?.let { cmd ->
            AppController.scope.launch {
                val r = AppController.runner.run(listOf("/bin/bash", "-lc", cmd), timeoutSec = 600)
                r.output.lines().forEach { android.util.Log.i("HermesLocalCI", "guest: $it") }
                android.util.Log.i("HermesLocalCI", "guest-exit: ${r.exitCode}")
            }
        }
        val pw = intent.getStringExtra("ci_password")
        val toggle = if (intent.hasExtra("ci_toggle")) intent.getBooleanExtra("ci_toggle", false) else null
        AppController.scope.launch {
            // Wait for the first-run install to finish.
            EngineState.status.first { it.phase != dev.syed.hermeslocal.engine.Phase.Installing && it.phase != dev.syed.hermeslocal.engine.Phase.NotInstalled }
            if (pw != null) android.util.Log.i("HermesLocalCI", "password: ${AppController.setPassword("admin", pw) ?: "ok"}")
            if (toggle != null) AppController.toggle(toggle)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        AppController.ensureInstalled()
        handleCiHooks(intent)
        setContent {
            val settings by AppController.settings.state.collectAsStateWithLifecycle()
            val status by EngineState.status.collectAsStateWithLifecycle()
            HermesTheme(amoled = settings.amoled, dynamic = settings.dynamicColor) {
                var screen by rememberSaveable { mutableStateOf(Screen.Main) }
                if (BuildConfig.CI_HOOKS) {
                    val ci by ciScreen.collectAsStateWithLifecycle()
                    LaunchedEffect(ci) { ci?.let { screen = it; ciScreen.value = null } }
                }
                val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                BackHandler(enabled = screen != Screen.Main) { screen = if (screen == Screen.Logs) Screen.Settings else Screen.Main }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
                    AnimatedContent(
                        targetState = screen,
                        transitionSpec = {
                            val forward = targetState != Screen.Main
                            val slide = spring<IntOffset>(dampingRatio = 0.9f, stiffness = 500f)
                            (slideInHorizontally(slide) { w -> if (forward) w / 4 else -w / 4 } + fadeIn()) togetherWith
                                (slideOutHorizontally(slide) { w -> if (forward) -w / 4 else w / 4 } + fadeOut())
                        },
                        label = "screen",
                    ) { s ->
                        when (s) {
                            Screen.Main -> MainScreen(status, settings, onNavigate = { screen = it })
                            Screen.Settings -> SettingsScreen(status, settings, onBack = { screen = Screen.Main }, onNavigate = { screen = it })
                            Screen.Providers -> ProvidersScreen(status, onBack = { screen = Screen.Main })
                            Screen.Logs -> LogsScreen(onBack = { screen = Screen.Settings })
                        }
                    }
                }
            }
        }
    }
}
