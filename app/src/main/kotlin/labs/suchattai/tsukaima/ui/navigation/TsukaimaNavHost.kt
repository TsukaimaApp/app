package labs.suchattai.tsukaima.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import labs.suchattai.tsukaima.ui.components.IconButton44
import labs.suchattai.tsukaima.ui.components.IconName
import labs.suchattai.tsukaima.ui.components.PrimaryButton
import labs.suchattai.tsukaima.ui.theme.Motion
import labs.suchattai.tsukaima.ui.theme.TsukaimaTheme
import labs.suchattai.tsukaima.ui.theme.TsukaimaType
import labs.suchattai.tsukaima.ui.theme.tsukaima

/**
 * Every [Route], cross-fading 220 ms between screens. Screens not built yet are titled placeholders; later
 * tasks swap in the real bodies. [showMenu] is false on the wide layout, where the drawer is a persistent pane.
 */
@Composable
fun TsukaimaNavHost(
    navController: NavHostController,
    startRoute: String,
    showMenu: Boolean,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fade = tween<Float>(Motion.screen, easing = Motion.easing)
    val back: () -> Unit = { navController.popBackStack() }
    NavHost(
        navController = navController,
        startDestination = startRoute,
        modifier = modifier,
        enterTransition = { fadeIn(fade) },
        exitTransition = { fadeOut(fade) },
        popEnterTransition = { fadeIn(fade) },
        popExitTransition = { fadeOut(fade) },
    ) {
        composable(Route.Onboarding.path) {
            PlaceholderScreen("Tsukaima") {
                PrimaryButton("Connect", onClick = { navController.navigate(Route.Settings.path) })
            }
        }
        composable(Route.Status.path) {
            PlaceholderScreen("Status", menu = showMenu, onMenu = onMenu)
        }
        composable(
            Route.Chat.path,
            arguments = listOf(
                navArgument(Route.ARG_SESSION) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) {
            PlaceholderScreen("Chat", menu = showMenu, onMenu = onMenu)
        }
        composable(Route.Cron.path) { PlaceholderScreen("Cron", onBack = back) }
        composable(
            Route.CronEdit.path,
            arguments = listOf(
                navArgument(Route.ARG_ID) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) {
            PlaceholderScreen("Edit job", onBack = back)
        }
        composable(Route.Settings.path) { PlaceholderScreen("Settings", onBack = back) }
        composable(Route.Logs.path) { PlaceholderScreen("Logs", onBack = back) }
    }
}

/**
 * Navigates to a drawer destination. Status is home: the target replaces everything above Status (a chat
 * replaces the previous chat), so Back from any drawer destination returns to Status. When Status is not on
 * the back stack (first run started at Onboarding), the stack is rebuilt as Status → [route].
 */
fun NavController.navigateTop(route: String) {
    val hasStatus = runCatching { getBackStackEntry(Route.Status.path) }.isSuccess
    if (!hasStatus) {
        navigate(Route.Status.path) {
            popUpTo(graph.findStartDestination().id) { inclusive = true }
            launchSingleTop = true
        }
    }
    if (route == Route.Status.path) {
        if (hasStatus) popBackStack(Route.Status.path, inclusive = false)
        return
    }
    navigate(route) {
        popUpTo(Route.Status.path)
        launchSingleTop = true
    }
}

/** Header (menu or back, title) over an empty page: the frame later tasks replace. */
@Composable
private fun PlaceholderScreen(
    title: String,
    menu: Boolean = false,
    onMenu: () -> Unit = {},
    onBack: (() -> Unit)? = null,
    content: @Composable () -> Unit = {},
) {
    val colors = MaterialTheme.tsukaima
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.surfacePage),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            when {
                onBack != null -> IconButton44(IconName.ArrowBack, "Back", onBack)
                menu -> IconButton44(IconName.Menu, "Open sessions", onMenu)
            }
            Text(
                title,
                style = TsukaimaType.title,
                color = colors.ink1,
                modifier = Modifier.padding(start = if (onBack == null && !menu) 12.dp else 0.dp),
            )
        }
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) { content() }
    }
}

@Composable
private fun PlaceholderPreview(dark: Boolean) {
    TsukaimaTheme(darkTheme = dark) { PlaceholderScreen("Status", menu = true) }
}

@Preview(name = "Placeholder light", heightDp = 240)
@Composable
private fun PlaceholderLightPreview() = PlaceholderPreview(dark = false)

@Preview(name = "Placeholder dark", heightDp = 240)
@Composable
private fun PlaceholderDarkPreview() = PlaceholderPreview(dark = true)
