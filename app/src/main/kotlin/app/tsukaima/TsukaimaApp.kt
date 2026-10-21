package app.tsukaima

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.tsukaima.di.AppContainer
import app.tsukaima.diagnostics.Diagnostics
import app.tsukaima.notifications.Channels

class TsukaimaApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        Diagnostics.install(this) // first: a crash anywhere below is recorded for the next launch
        container = AppContainer(this)
        Channels.register(this)
        container.notifications.start()
        // Poll and hold the gateway while some activity is started: the process lifecycle's ON_STOP is
        // debounced across configuration changes, so a rotation does not drop the connection. On ON_STOP the
        // connection is handed to GatewayService (same socket, slower poll) when BackgroundPolicy allows,
        // else stopped; see BackgroundController.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = container.background.onForeground()
                override fun onStop(owner: LifecycleOwner) {
                    container.background.onBackground()
                }
            },
        )
    }
}
