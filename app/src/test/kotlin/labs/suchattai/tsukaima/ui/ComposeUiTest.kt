package labs.suchattai.tsukaima.ui

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import labs.suchattai.tsukaima.ui.theme.TsukaimaTheme
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Base for the JVM Compose UI tests: Robolectric (SDK 34, native graphics) hosting [compose]'s empty activity. The
 * plain [Application] stands in for `TsukaimaApp`, whose `onCreate` builds the whole `AppContainer` (keystore-backed
 * prefs, notifications, the gateway) that none of these screens need; the theme comes from [TsukaimaTheme], not the
 * manifest. Each test renders a stateless composable with fake state and no-op actions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class ComposeUiTest {
    @get:Rule
    val compose: ComposeContentTestRule = createComposeRule()

    /** Sets [content] inside the app theme ([dark] or warm). */
    protected fun setThemed(dark: Boolean = false, content: @Composable () -> Unit) =
        compose.setContent { TsukaimaTheme(darkTheme = dark, content = content) }
}
