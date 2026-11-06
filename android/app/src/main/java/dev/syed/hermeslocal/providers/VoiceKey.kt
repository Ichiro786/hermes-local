package dev.syed.hermeslocal.providers

import dev.syed.hermeslocal.AppController
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Engine voice settings (`hermes-local-voice`): what Live Mode hears/speaks with, the optional voice keys and
 *  which engine answers the Hermes app's GPT-Live mode (ChatGPT/Codex sign-in, OpenAI key or Gemini Live). */
data class VoiceStatus(
    val sttProvider: String = "",
    /** What speech-to-text really uses: openai | gemini | none (or another Hermes provider name). */
    val sttBackend: String = "",
    val ttsProvider: String = "",
    val ttsModel: String = "",
    val openAiKey: Boolean = false,
    val keyHint: String = "",
    val geminiKey: Boolean = false,
    val geminiHint: String = "",
    val gptLive: Boolean = false,
    val liveReason: String = "",
    /** codex | openai | gemini | none: the engine GPT-Live uses now. */
    val liveBackend: String = "",
    /** auto | codex | openai | gemini: the user's choice. */
    val liveSelected: String = "auto",
    /** Engines that have credentials, in automatic order. */
    val liveBackends: List<String> = emptyList(),
    val liveModel: String = "",
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
                    sttProvider = o.optString("stt_provider"), sttBackend = o.optString("stt_backend"),
                    ttsProvider = o.optString("tts_provider"), ttsModel = o.optString("tts_model"),
                    openAiKey = o.optInt("openai_key") == 1, keyHint = o.optString("openai_key_hint"),
                    geminiKey = o.optInt("gemini_key") == 1, geminiHint = o.optString("gemini_key_hint"),
                    gptLive = o.optInt("gpt_live") == 1, liveReason = o.optString("gpt_live_reason"),
                    liveBackend = o.optString("live_backend"), liveSelected = o.optString("live_selected", "auto").ifEmpty { "auto" },
                    liveBackends = o.optJSONArray("live_backends")?.let { a -> List(a.length()) { a.optString(it) } } ?: emptyList(),
                    liveModel = o.optString("live_model"),
                    error = if (o.has("error")) o.optString("error") else null,
                )
            } catch (e: Exception) {
                VoiceStatus(error = "bad status: ${e.message}")
            }
        }
    }
}

/** The optional voice API keys: what `hermes-local-voice <kind>-key set|clear` stores. */
enum class VoiceKeyKind(val arg: String) { OpenAi("openai"), Gemini("gemini") }

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
    suspend fun save(kind: VoiceKeyKind, key: String): String? = apply(listOf("${kind.arg}-key", "set"), key.trim() + "\n")

    suspend fun clear(kind: VoiceKeyKind): String? = apply(listOf("${kind.arg}-key", "clear"), null)

    /** auto | codex | openai | gemini: which engine answers the Hermes app's GPT-Live mode. */
    suspend fun setLiveBackend(value: String): String? = apply(listOf("live-backend", "set", value), null)

    private suspend fun apply(args: List<String>, stdin: String?): String? {
        val r = AppController.runner.run(VOICE + args, stdin = stdin, timeoutSec = 150)
        val s = VoiceStatus.parse(r.output)
        if (s.error == null) _status.value = s else refresh() // a refused key keeps the saved state on screen
        return s.error ?: if (r.exitCode == 0) null else "could not save (exit ${r.exitCode})"
    }
}
