package labs.suchattai.tsukaima

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import labs.suchattai.tsukaima.di.AppContainer

class TsukaimaApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Poll and hold the gateway only while some activity is started: the process lifecycle's ON_STOP is
        // debounced across configuration changes, so a rotation does not drop the connection.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = container.connection.start()
                override fun onStop(owner: LifecycleOwner) = container.connection.stop()
            },
        )
    }
}
