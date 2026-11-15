package run.hermes.empryo.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the engine after boot when "Start on boot" is on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (Settings.get(context).value.startOnBoot && Paths(context).isInstalled()) runCatching { EngineService.start(context) }
    }
}
