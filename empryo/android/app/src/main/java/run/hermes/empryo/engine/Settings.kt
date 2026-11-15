package run.hermes.empryo.engine

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SettingsSnapshot(
    val bridgePort: Int = 8787,
    val lanAccess: Boolean = false,
    val pairOfficial: Boolean = false,
    val startOnBoot: Boolean = false,
    val keepAwake: Boolean = false,
    val defaultModel: String = "",
)

/** App settings (SharedPreferences) as a StateFlow so the UI and the service share one source. */
class Settings private constructor(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<SettingsSnapshot> = _state.asStateFlow()
    val value get() = _state.value

    private fun read() = SettingsSnapshot(
        bridgePort = prefs.getInt("port", 8787),
        lanAccess = prefs.getBoolean("lan", false),
        pairOfficial = prefs.getBoolean("pair", false),
        startOnBoot = prefs.getBoolean("boot", false),
        keepAwake = prefs.getBoolean("keep_awake", false),
        defaultModel = prefs.getString("model", "") ?: "",
    )

    fun update(block: SettingsSnapshot.() -> SettingsSnapshot) {
        val s = _state.value.block()
        prefs.edit()
            .putInt("port", s.bridgePort.coerceIn(1024, 65535))
            .putBoolean("lan", s.lanAccess)
            .putBoolean("pair", s.pairOfficial)
            .putBoolean("boot", s.startOnBoot)
            .putBoolean("keep_awake", s.keepAwake)
            .putString("model", s.defaultModel.trim())
            .apply()
        _state.value = read()
    }

    companion object {
        @Volatile private var instance: Settings? = null
        fun get(context: Context): Settings =
            instance ?: synchronized(this) { instance ?: Settings(context.applicationContext).also { instance = it } }
    }
}
