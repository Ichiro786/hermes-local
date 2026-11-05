package dev.syed.hermeslocal.engine

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SettingsSnapshot(
    val port: Int = 9119,
    val lanAccess: Boolean = false,
    val apiServer: Boolean = false,
    val apiPort: Int = 8642,
    val startOnBoot: Boolean = false,
    val keepAwake: Boolean = true,
    val autoRestart: Boolean = true,
    val amoled: Boolean = true,
    val dynamicColor: Boolean = true,
    val username: String = "admin",
    val passwordSet: Boolean = false,
    val engineUrl: String = "",
)

/** App settings (SharedPreferences) exposed as a StateFlow so the UI and the service share one source. */
class Settings private constructor(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<SettingsSnapshot> = _state.asStateFlow()
    val value get() = _state.value

    /** True while the user wants the engine on (restored after reboot / service restart). */
    var wantRunning: Boolean
        get() = prefs.getBoolean("want_running", false)
        set(v) = prefs.edit().putBoolean("want_running", v).apply()

    private fun read() = SettingsSnapshot(
        port = prefs.getInt("port", 9119),
        lanAccess = prefs.getBoolean("lan", false),
        apiServer = prefs.getBoolean("api_server", false),
        apiPort = prefs.getInt("api_port", 8642),
        startOnBoot = prefs.getBoolean("boot", false),
        keepAwake = prefs.getBoolean("keep_awake", true),
        autoRestart = prefs.getBoolean("auto_restart", true),
        amoled = prefs.getBoolean("amoled", true),
        dynamicColor = prefs.getBoolean("dynamic_color", true),
        username = prefs.getString("username", "admin") ?: "admin",
        passwordSet = prefs.getBoolean("password_set", false),
        engineUrl = prefs.getString("engine_url", "") ?: "",
    )

    fun update(block: SettingsSnapshot.() -> SettingsSnapshot) {
        val s = _state.value.block()
        prefs.edit()
            .putInt("port", s.port.coerceIn(1024, 65535))
            .putBoolean("lan", s.lanAccess)
            .putBoolean("api_server", s.apiServer)
            .putInt("api_port", s.apiPort.coerceIn(1024, 65535))
            .putBoolean("boot", s.startOnBoot)
            .putBoolean("keep_awake", s.keepAwake)
            .putBoolean("auto_restart", s.autoRestart)
            .putBoolean("amoled", s.amoled)
            .putBoolean("dynamic_color", s.dynamicColor)
            .putString("username", s.username)
            .putBoolean("password_set", s.passwordSet)
            .putString("engine_url", s.engineUrl)
            .apply()
        _state.value = read()
    }

    companion object {
        @Volatile private var instance: Settings? = null
        fun get(context: Context): Settings =
            instance ?: synchronized(this) { instance ?: Settings(context.applicationContext).also { instance = it } }
    }
}
