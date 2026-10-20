package labs.suchattai.tsukaima

import android.app.Application
import labs.suchattai.tsukaima.di.AppContainer

class TsukaimaApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Polling and gateway management run on the container's background scope; returns immediately.
        container.connection.start()
    }
}
