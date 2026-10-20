package labs.suchattai.tsukaima

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import labs.suchattai.tsukaima.di.AppContainer
import labs.suchattai.tsukaima.notifications.BackgroundPolicy
import java.io.IOException
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import labs.suchattai.tsukaima.data.prefs.ThemeMode
import labs.suchattai.tsukaima.ui.shell.AppShell
import labs.suchattai.tsukaima.ui.theme.TsukaimaTheme

class MainActivity : ComponentActivity() {
    /** The answer needs no handling: the background policy reads the live permission state. */
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as TsukaimaApp).container
        askForNotificationsOnce(container)
        setContent {
            val settings by container.settings.collectAsStateWithLifecycle()
            // Until settings load, follow the system (the shell shows only the page background meanwhile).
            val dark = when (settings?.theme ?: ThemeMode.System) {
                ThemeMode.Warm -> false
                ThemeMode.Dark -> true
                ThemeMode.System -> isSystemInDarkTheme()
            }
            SystemBarIcons(dark)
            TsukaimaTheme(darkTheme = dark, dynamicColor = settings?.materialYou == true) {
                AppShell(container)
            }
        }
    }

    /**
     * Requests `POST_NOTIFICATIONS` once, after onboarding completes (API 33+). The "asked" flag is saved
     * first, so a denial or a dismissed dialog is never re-prompted.
     */
    private fun askForNotificationsOnce(container: AppContainer) {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val settings = container.settingsStore.settings.first { it.onboarded }
                if (!BackgroundPolicy.shouldRequestPermission(Build.VERSION.SDK_INT, settings, granted())) return@repeatOnLifecycle
                try {
                    container.settingsStore.update { it.copy(askedNotifications = true) }
                } catch (e: IOException) {
                    Log.w("Tsukaima", "saving notification-permission flag failed", e)
                }
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun granted(): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Transparent bars whose icons contrast with the app's theme (not the system's). */
    @Composable
    private fun SystemBarIcons(dark: Boolean) {
        LaunchedEffect(dark) {
            val style = if (dark) {
                SystemBarStyle.dark(Color.TRANSPARENT)
            } else {
                SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
            }
            enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        }
    }
}
