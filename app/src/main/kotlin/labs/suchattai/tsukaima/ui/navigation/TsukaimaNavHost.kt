package labs.suchattai.tsukaima.ui.navigation

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.core.net.toUri
import androidx.lifecycle.ViewModelStoreOwner
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import labs.suchattai.tsukaima.data.ServerChanges
import labs.suchattai.tsukaima.data.ticks
import labs.suchattai.tsukaima.di.AppContainer
import labs.suchattai.tsukaima.ui.chat.ChatScreen
import labs.suchattai.tsukaima.ui.chat.ChatViewModel
import labs.suchattai.tsukaima.ui.chat.ContainerChatBackend
import labs.suchattai.tsukaima.ui.cron.CronEditScreen
import labs.suchattai.tsukaima.ui.cron.CronScreen
import labs.suchattai.tsukaima.ui.cron.CronViewModel
import labs.suchattai.tsukaima.ui.cron.HermesCronSource
import labs.suchattai.tsukaima.ui.logs.HermesLogsSource
import labs.suchattai.tsukaima.ui.logs.LogsScreen
import labs.suchattai.tsukaima.ui.logs.LogsViewModel
import labs.suchattai.tsukaima.ui.onboarding.OnboardingScreen
import labs.suchattai.tsukaima.ui.settings.SettingsScreen
import labs.suchattai.tsukaima.ui.settings.SettingsViewModel
import labs.suchattai.tsukaima.ui.status.HermesStatusSource
import labs.suchattai.tsukaima.ui.status.StatusScreen
import labs.suchattai.tsukaima.ui.status.StatusViewModel
import labs.suchattai.tsukaima.ui.theme.Motion

/**
 * Every [Route], cross-fading 220 ms between screens. [showMenu] is false on the wide layout, where the drawer is
 * a persistent pane.
 */
@Composable
fun TsukaimaNavHost(
    container: AppContainer,
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
            OnboardingScreen(onConnect = { navController.navigate(Route.Settings.path) })
        }
        composable(Route.Status.path) {
            val vm: StatusViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        StatusViewModel(
                            HermesStatusSource(
                                container.repository,
                                container.connection,
                                container.gateway.events.ticks(ServerChanges.PLATFORMS),
                                configuredUrl = { container.settings.value?.serverUrl.orEmpty() },
                            ),
                            toast = container.toastState::show,
                        )
                    }
                },
            )
            StatusScreen(
                vm = vm,
                onMenu = onMenu,
                showMenu = showMenu,
                onSettings = { navController.navigate(Route.Settings.path) },
                onNewChat = { navController.navigateTop(Route.Chat.create()) },
                onCron = { navController.navigateTop(Route.Cron.path) },
                onContinue = { navController.navigateTop(Route.Chat.create(it)) },
                onOpenCached = { navController.navigateTop(Route.Chat.create(it)) },
            )
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
        ) { entry ->
            val session = entry.arguments?.getString(Route.ARG_SESSION)
            // Keyed by the session too, so a VM is never reused for a different chat.
            val vm: ChatViewModel = viewModel(
                key = "chat-$session",
                factory = viewModelFactory { initializer { ChatViewModel(ContainerChatBackend(container), session) } },
            )
            ChatScreen(
                vm = vm,
                onMenu = onMenu,
                showMenu = showMenu,
                onStatus = { navController.navigateTop(Route.Status.path) },
                onDeleted = { navController.navigateTop(Route.Chat.create()) },
                visibleSession = container.visibleLiveSessionId,
            )
        }
        composable(Route.Cron.path) {
            CronScreen(
                vm = cronViewModel(container),
                onBack = back,
                onEdit = { navController.navigate(Route.CronEdit.create(it?.id)) },
            )
        }
        composable(
            Route.CronEdit.path,
            arguments = listOf(
                navArgument(Route.ARG_ID) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            // Shares the list's ViewModel (the open draft lives there); falls back to its own, which closes at once.
            val owner = remember(entry) { runCatching { navController.getBackStackEntry(Route.Cron.path) }.getOrDefault(entry) }
            CronEditScreen(vm = cronViewModel(container, owner), onClose = back)
        }
        composable(Route.Settings.path) {
            val context = LocalContext.current
            val vm: SettingsViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        SettingsViewModel(
                            container.repository, container.settingsStore, container.credentials, container.connection,
                            onSignedOut = container::signedOut,
                            toast = container.toastState::show,
                            voiceLanguages = container::voiceLanguages,
                        )
                    }
                },
            )
            LaunchedEffect(vm) {
                // First run: sign-in finished from Onboarding, so Settings is the only screen; go home.
                vm.signedIn.collect {
                    val hasStatus = runCatching { navController.getBackStackEntry(Route.Status.path) }.isSuccess
                    if (!hasStatus) navController.navigateTop(Route.Status.path)
                }
            }
            LaunchedEffect(vm) {
                // Signed out: back to first run, with nothing (chats, status) left on the back stack.
                vm.signedOut.collect {
                    navController.navigate(Route.Onboarding.path) {
                        popUpTo(navController.graph.id) { inclusive = true }
                    }
                }
            }
            SettingsScreen(
                vm = vm,
                onBack = back,
                onLogs = { navController.navigate(Route.Logs.path) },
                onOpenDashboard = {
                    val url = container.settings.value?.serverUrl.orEmpty()
                    if (url.isEmpty()) {
                        container.toastState.show("Sign in first")
                    } else {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                        } catch (_: ActivityNotFoundException) {
                            container.toastState.show("No browser to open $url")
                        }
                    }
                },
            )
        }
        composable(Route.Logs.path) {
            val vm: LogsViewModel = viewModel(
                factory = viewModelFactory { initializer { LogsViewModel(HermesLogsSource(container.repository)) } },
            )
            LogsScreen(vm = vm, onBack = back)
        }
    }
}

@Composable
private fun cronViewModel(container: AppContainer, owner: ViewModelStoreOwner? = null): CronViewModel {
    val factory = viewModelFactory {
        initializer { CronViewModel(HermesCronSource(container.repository)) { container.toastState.show(it) } }
    }
    return if (owner == null) viewModel(factory = factory) else viewModel(owner, factory = factory)
}

/**
 * Navigates to a drawer destination. Status is home: the target replaces everything above Status (a chat
 * replaces the previous chat with a new back-stack entry), so Back from any drawer destination returns to Status. When Status is not on
 * the back stack (first run, or after a sign-out, started at Onboarding), the stack is rebuilt as Status → [route].
 */
fun NavController.navigateTop(route: String) {
    val hasStatus = runCatching { getBackStackEntry(Route.Status.path) }.isSuccess
    if (!hasStatus) {
        navigate(Route.Status.path) {
            // The whole stack: the start destination may not be on it (after a sign-out it is Onboarding → Settings).
            popUpTo(graph.id) { inclusive = true }
            launchSingleTop = true
        }
    }
    if (route == Route.Status.path) {
        if (hasStatus) popBackStack(Route.Status.path, inclusive = false)
        return
    }
    navigate(route) {
        popUpTo(Route.Status.path)
        // A chat always gets a fresh entry (and so a fresh ChatViewModel): single-top would hand chat X's
        // entry the arguments of chat Y. popUpTo already keeps chats from stacking.
        launchSingleTop = route != Route.Chat.create() && !route.startsWith(Route.Chat.create() + "?")
    }
}
