package labs.suchattai.tsukaima

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import labs.suchattai.tsukaima.data.prefs.ThemeMode
import labs.suchattai.tsukaima.ui.shell.AppShell
import labs.suchattai.tsukaima.ui.theme.TsukaimaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as TsukaimaApp).container
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
