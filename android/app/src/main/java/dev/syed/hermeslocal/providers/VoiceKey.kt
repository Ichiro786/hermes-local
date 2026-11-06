package dev.syed.hermeslocal.providers

import dev.syed.hermeslocal.AppController
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Engine voice settings (`hermes-local-voice`): what Live Mode hears/speaks with, and the optional GPT-Live key. */
data class VoiceStatus(
    val sttProvider: String = "",
    val sttModel: String = "",
    val ttsProvider: String = "",
    val openAiKey: Boolean = false,
    val keyHint: String = "",
    val gptLive: Boolean = false,
    val error: String? = null,
) {
    companion object {
        fun parse(output: String): VoiceStatus {
            val json = output.lines().lastOrNull { it.trim().startsWith("{") }
                ?: return VoiceStatus(error = if ("No such file" in output) "Update the engine to use voice settings."
                    else output.trim().takeLast(200).ifEmpty { "no answer from the engine" })
            return try {
                val o = JSONObject(json)
                if (!o.optBoolean("ok", true) && !o.has("stt_provider")) return VoiceStatus(error = o.optString("error"))
                VoiceStatus(
                    sttProvider = o.optString("stt_provider"), sttModel = o.optString("stt_model"),
                    ttsProvider = o.optString("tts_provider"), openAiKey = o.optInt("openai_key") == 1,
                    keyHint = o.optString("openai_key_hint"), gptLive = o.optInt("gpt_live") == 1,
                    error = if (o.has("error")) o.optString("error") else null,
                )
            } catch (e: Exception) {
                VoiceStatus(error = "bad status: ${e.message}")
            }
        }
    }
}

object VoiceKey {
    private val VOICE = listOf("/bin/bash", "/opt/hermes-local/bin/hermes-local-voice")
    private val _status = MutableStateFlow<VoiceStatus?>(null)
    val status: StateFlow<VoiceStatus?> = _status.asStateFlow()
    private var job: Job? = null

    fun refresh() {
        job?.cancel()
        job = AppController.scope.launch {
            _status.value = VoiceStatus.parse(AppController.runner.run(VOICE + "status", timeoutSec = 150).output)
        }
    }

    /** Saves [key] (sent on stdin, never on the command line). Returns an error or null. */
    suspend fun save(key: String): String? = apply(listOf("openai-key", "set"), key.trim() + "\n")

    suspend fun clear(): String? = apply(listOf("openai-key", "clear"), null)

    private suspend fun apply(args: List<String>, stdin: String?): String? {
        val r = AppController.runner.run(VOICE + args, stdin = stdin, timeoutSec = 150)
        val s = VoiceStatus.parse(r.output)
        if (s.error == null) _status.value = s else refresh() // a refused key keeps the saved state on screen
        return s.error ?: if (r.exitCode == 0) null else "could not save (exit ${r.exitCode})"
    }
}
