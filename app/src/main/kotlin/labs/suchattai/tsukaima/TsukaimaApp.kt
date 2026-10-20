package labs.suchattai.tsukaima

import android.app.Application
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.launch
import labs.suchattai.tsukaima.di.AppContainer
import labs.suchattai.tsukaima.notifications.BackgroundPolicy
import labs.suchattai.tsukaima.notifications.Channels
import labs.suchattai.tsukaima.notifications.GatewayService

class TsukaimaApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Channels.register(this)
        container.notifications.start()
        // Poll and hold the gateway only while some activity is started: the process lifecycle's ON_STOP is
        // debounced across configuration changes, so a rotation does not drop the connection. In the
        // background, GatewayService keeps it (at a slower poll) when BackgroundPolicy allows.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    container.inForeground.value = true
                    container.connection.start()
                    GatewayService.stop(this@TsukaimaApp)
                }

                override fun onStop(owner: LifecycleOwner) {
                    container.inForeground.value = false
                    container.connection.stop()
                    maybeStartService()
                }
            },
        )
    }

    /** Off the main thread: the credential check may touch the Keystore. */
    private fun maybeStartService() {
        container.appScope.launch {
            val start = BackgroundPolicy.shouldStartService(
                settings = container.settings.value,
                hasPassword = container.credentials.password() != null,
                authRequired = container.connection.authRequired.value,
                notificationsPermitted = NotificationManagerCompat.from(this@TsukaimaApp).areNotificationsEnabled(),
                foreground = container.inForeground.value,
            )
            if (start) GatewayService.start(this@TsukaimaApp)
        }
    }
}
