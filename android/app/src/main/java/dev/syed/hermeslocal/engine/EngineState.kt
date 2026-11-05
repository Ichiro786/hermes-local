package dev.syed.hermeslocal.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class Phase { NotInstalled, Installing, Stopped, Starting, Running, Stopping, Failed }

data class EngineStatus(
    val phase: Phase = Phase.Stopped,
    /** System.currentTimeMillis() when the dashboard first answered. */
    val runningSince: Long = 0L,
    val port: Int = 9119,
    val lan: Boolean = false,
    val message: String? = null,
    val installStage: String? = null,
    /** 0..1, or -1 when indeterminate. */
    val installProgress: Float = -1f,
) {
    val isOn get() = phase == Phase.Starting || phase == Phase.Running
    val localUrl get() = "http://127.0.0.1:$port"
}

/** Process-wide engine state. The service and the installer write it; the UI reads it. */
object EngineState {
    private val _status = MutableStateFlow(EngineStatus())
    val status: StateFlow<EngineStatus> = _status.asStateFlow()

    fun set(transform: (EngineStatus) -> EngineStatus) = _status.update(transform)

    /** Last log lines (live view); the full log is on disk. */
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    fun appendLine(line: String) = _lines.update { (if (it.size >= 400) it.drop(it.size - 399) else it) + line }
    fun clearLines() { _lines.value = emptyList() }
}
