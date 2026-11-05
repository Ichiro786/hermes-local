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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.syed.hermeslocal.engine.EngineState
import dev.syed.hermeslocal.ui.HermesTheme
import dev.syed.hermeslocal.ui.LogsScreen
import dev.syed.hermeslocal.ui.MainScreen
import dev.syed.hermeslocal.ui.ProvidersScreen
import dev.syed.hermeslocal.ui.SettingsScreen

enum class Screen { Main, Settings, Providers, Logs }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        AppController.ensureInstalled()
        setContent {
            val settings by AppController.settings.state.collectAsStateWithLifecycle()
            val status by EngineState.status.collectAsStateWithLifecycle()
            HermesTheme(amoled = settings.amoled, dynamic = settings.dynamicColor) {
                var screen by rememberSaveable { mutableStateOf(Screen.Main) }
                val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                BackHandler(enabled = screen != Screen.Main) { screen = if (screen == Screen.Logs) Screen.Settings else Screen.Main }
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
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
