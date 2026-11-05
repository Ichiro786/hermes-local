package dev.syed.hermeslocal.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts Hermes after boot (setting "Start on boot") or after an app update if it was running. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val settings = Settings.get(context)
        val paths = EnginePaths(context)
        if (!paths.isInstalled() || !settings.value.passwordSet) return
        val start = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> settings.value.startOnBoot
            Intent.ACTION_MY_PACKAGE_REPLACED -> settings.wantRunning
            else -> false
        }
        if (start) runCatching { EngineService.start(context) }
    }
}
