package run.hermes.empryo.engine

import org.json.JSONObject

/**
 * Phone defaults merged into ~/.empryo/config.json before every start (other keys are left alone):
 * telemetry and background downloads off, no cache pings or prewarm, plain sh for tools, at most 3
 * parallel agents, the bridge port, and the user's default model when set.
 */
object EngineConfig {
    fun apply(paths: Paths, s: SettingsSnapshot) {
        val f = paths.configJson
        f.parentFile?.mkdirs()
        val root = runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject())
        root.put("telemetry", false)
        root.put("liveTranslations", false)
        root.put("liveSkills", false)
        root.put("lspAutoInstall", false)
        root.put("autoDownloadUpdates", false)
        root.put("autoInstallUpdates", false)
        root.put("agentShell", "sh")
        root.put("remotePort", s.bridgePort)
        obj(root, "soulstack").put("autoUpdate", false)
        obj(root, "cache").put("keepAlive", false).put("prewarm", false)
        obj(root, "taskRouter").put("maxConcurrentAgents", 3)
        if (s.defaultModel.isNotBlank()) root.put("defaultModel", s.defaultModel.trim())
        f.writeText(root.toString(2))
    }

    private fun obj(root: JSONObject, key: String): JSONObject =
        root.optJSONObject(key) ?: JSONObject().also { root.put(key, it) }
}
