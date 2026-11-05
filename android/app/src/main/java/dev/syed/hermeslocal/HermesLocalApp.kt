package dev.syed.hermeslocal

import android.app.Application
import dev.syed.hermeslocal.engine.EnginePaths
import dev.syed.hermeslocal.engine.EngineService
import dev.syed.hermeslocal.engine.EngineState
import dev.syed.hermeslocal.engine.Phase

class HermesLocalApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppController.init(this)
        EngineService.createChannel(this)
        if (!EnginePaths(this).isInstalled()) EngineState.set { it.copy(phase = Phase.NotInstalled) }
    }
}
