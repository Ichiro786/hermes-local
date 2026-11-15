package run.hermes.empryo.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class Phase { NotInstalled, Installing, Stopped, Starting, Running, Stopping, Failed }

data class EngineStatus(
    val phase: Phase = Phase.Stopped,
    /** System.currentTimeMillis() when ACP initialize answered. */
    val runningSince: Long = 0L,
    val message: String? = null,
    val installStage: String? = null,
    /** 0..1, or -1 when indeterminate. */
    val installProgress: Float = -1f,
    /** Official-app bridge (phone-service): null when not asked for. */
    val bridge: String? = null,
    val turnActive: Boolean = false,
) {
    val isOn get() = phase == Phase.Starting || phase == Phase.Running
}

/** Process-wide engine state. The service and the installer write it; the UI reads it. */
object EngineState {
    private val _status = MutableStateFlow(EngineStatus())
    val status: StateFlow<EngineStatus> = _status.asStateFlow()

    fun set(transform: (EngineStatus) -> EngineStatus) = _status.update(transform)
}
