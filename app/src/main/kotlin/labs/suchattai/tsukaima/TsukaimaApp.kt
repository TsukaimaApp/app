package labs.suchattai.tsukaima

import android.app.Application
import labs.suchattai.tsukaima.di.AppContainer

class TsukaimaApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
