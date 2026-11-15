package run.hermes.empryo

import android.app.Application
import run.hermes.empryo.engine.EngineService
import run.hermes.empryo.engine.EngineState
import run.hermes.empryo.engine.Paths
import run.hermes.empryo.engine.Phase

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppController.init(this)
        EngineService.createChannel(this)
        if (!Paths(this).isInstalled()) EngineState.set { it.copy(phase = Phase.NotInstalled) }
    }
}
