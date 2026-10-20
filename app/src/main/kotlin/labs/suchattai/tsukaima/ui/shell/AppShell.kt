package labs.suchattai.tsukaima.ui.shell

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import labs.suchattai.tsukaima.data.prefs.Settings
import labs.suchattai.tsukaima.di.AppContainer
import labs.suchattai.tsukaima.ui.components.ToastHost
import labs.suchattai.tsukaima.ui.drawer.DrawerViewModel
import labs.suchattai.tsukaima.ui.drawer.SessionsDrawer
import labs.suchattai.tsukaima.ui.navigation.Route
import labs.suchattai.tsukaima.ui.navigation.TsukaimaNavHost
import labs.suchattai.tsukaima.ui.navigation.navigateTop
import labs.suchattai.tsukaima.ui.theme.Dimens
import labs.suchattai.tsukaima.ui.theme.Motion
import labs.suchattai.tsukaima.ui.theme.tsukaima
import labs.suchattai.tsukaima.notifications.ApprovalNotifier
import kotlinx.coroutines.flow.filterNotNull

/**
 * The frame every screen lives in (HANDOFF §4, §8).
 *
 * - Until settings have loaded, only the page background (no NavHost), so the app never starts at Onboarding
 *   and then jumps. The start destination is decided once, from the first loaded settings.
 * - Phone (< 720dp): a [ModalNavigationDrawer] with the 296dp [SessionsDrawer], `scrim` token, 200 ms slide.
 * - Wide (≥ 720dp): a persistent 300dp drawer pane, hairline, and the screen centred at most 760dp wide. No pane
 *   on Onboarding.
 * - An auth failure routes to Settings with a toast. Hosts the app's [ToastHost].
 */
@Composable
fun AppShell(container: AppContainer) {
    val vm: AppViewModel = viewModel(factory = viewModelFactory { initializer { AppViewModel(container) } })
    val settings by vm.settings.collectAsStateWithLifecycle()
    val colors = MaterialTheme.tsukaima

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.surfacePage),
    ) {
        settings?.let { loaded -> LoadedShell(container, vm, loaded) }
        ToastHost(
            container.toastState,
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 84.dp),
        )
    }
}

@Composable
private fun LoadedShell(container: AppContainer, vm: AppViewModel, firstSettings: Settings) {
    val colors = MaterialTheme.tsukaima
    // Decided once from the first loaded settings; saveable so a recreated activity keeps the restored graph.
    val startRoute = rememberSaveable { if (firstSettings.onboarded) Route.Status.path else Route.Onboarding.path }
    val navController = rememberNavController()
    val drawerVm: DrawerViewModel = viewModel(
        factory = viewModelFactory { initializer { DrawerViewModel(container.repository, container.settings) { container.connection.hermes.value.reachable }
        } },
    )
    val drawerState by drawerVm.state.collectAsStateWithLifecycle()
    val drawerOpen by vm.drawerOpen.collectAsStateWithLifecycle()
    val wide = LocalConfiguration.current.screenWidthDp.dp >= Dimens.wideBreakpoint

    val entry by navController.currentBackStackEntryAsState()
    val currentRoute = entry?.destination?.route
    val selectedSession = if (currentRoute == Route.Chat.path) entry?.arguments?.getString(Route.ARG_SESSION) else null
    val onboarding = currentRoute == null || currentRoute == Route.Onboarding.path

    LaunchedEffect(selectedSession) { drawerVm.select(selectedSession) }
    LaunchedEffect(wide) { if (wide) vm.closeDrawer() } // the pane replaces the overlay; don't reopen it later
    // Phone: reload when the overlay opens. Wide: the pane is always visible, so reload on each screen change
    // (a chat created or archived elsewhere shows up when the user moves on).
    LaunchedEffect(drawerOpen) { if (drawerOpen) drawerVm.refresh() }
    LaunchedEffect(wide, entry?.id) { if (wide && entry != null) drawerVm.refresh() }
    LaunchedEffect(drawerVm) { drawerVm.errors.collect { container.toastState.show(it) } }
    LaunchedEffect(drawerVm, navController) {
        // The open chat was deleted or archived from the drawer: don't leave it on a dead id.
        drawerVm.openSessionRemoved.collect { id ->
            val entryNow = navController.currentBackStackEntry
            val openId = entryNow?.arguments?.getString(Route.ARG_SESSION)
            if (entryNow?.destination?.route == Route.Chat.path && openId == id) {
                navController.navigateTop(Route.Chat.create())
            }
        }
    }
    LaunchedEffect(navController) {
        // A notification asked for a screen: chat opens the session that asked (else the last conversation),
        // settings the pairing list.
        container.openScreen.filterNotNull().collect { request ->
            container.openScreen.value = null
            if (navController.currentBackStackEntry?.destination?.route == Route.Onboarding.path) return@collect
            when (request.screen) {
                ApprovalNotifier.SCREEN_CHAT -> {
                    val session = request.sessionId ?: container.settings.value?.lastSessionId
                    val entryNow = navController.currentBackStackEntry
                    val alreadyOpen = session != null && entryNow?.destination?.route == Route.Chat.path &&
                        entryNow.arguments?.getString(Route.ARG_SESSION) == session
                    if (!alreadyOpen) navController.navigateTop(Route.Chat.create(session))
                }
                ApprovalNotifier.SCREEN_SETTINGS -> navController.navigate(Route.Settings.path) { launchSingleTop = true }
            }
        }
    }
    LaunchedEffect(Unit) { container.connection.hints.collect { container.toastState.show(it) } }
    LaunchedEffect(vm, navController) {
        vm.authPrompts.collect {
            navController.navigate(Route.Settings.path) { launchSingleTop = true }
            container.toastState.show("Sign in again to reconnect")
        }
    }

    val go: (String) -> Unit = { route ->
        vm.closeDrawer()
        navController.navigateTop(route)
    }
    val drawer: @Composable (Dp) -> Unit = { width ->
        Row(Modifier.fillMaxHeight()) {
            SessionsDrawer(
                state = drawerState,
                onNewChat = { go(Route.Chat.create()) },
                onStatus = { go(Route.Status.path) },
                onCron = { go(Route.Cron.path) },
                onOpen = { id ->
                    if (id == selectedSession) vm.closeDrawer() else go(Route.Chat.create(id))
                },
                onRename = drawerVm::rename,
                onArchive = drawerVm::archive,
                onDelete = drawerVm::delete,
                onSettings = { go(Route.Settings.path) },
                onQuery = drawerVm::setQuery,
                onShowArchived = drawerVm::setShowArchived,
                modifier = Modifier.width(width),
            )
            VerticalDivider(thickness = 1.dp, color = colors.hairline)
        }
    }
    val screens: @Composable (Modifier) -> Unit = { modifier ->
        TsukaimaNavHost(
            container = container,
            navController = navController,
            startRoute = startRoute,
            showMenu = !wide,
            onMenu = vm::openDrawer,
            modifier = modifier.windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)),
        )
    }

    // One composition tree for both layouts: the NavHost keeps the same position (hence the same saveable
    // keys) whether the window is wide or not, so a fold/unfold recreate restores every screen's saved state.
    // Wide just disables the overlay and shows the pane beside the content.
    ShellDrawer(
        open = drawerOpen && !wide,
        enabled = !wide && !onboarding,
        onOpenChange = { if (it) vm.openDrawer() else vm.closeDrawer() },
        drawer = { if (!wide) drawer(Dimens.drawerWidth) },
    ) {
        Row(Modifier.fillMaxSize()) {
            if (wide && !onboarding) drawer(Dimens.paneWidth)
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.TopCenter,
            ) {
                screens(
                    Modifier
                        .then(if (wide) Modifier.widthIn(max = Dimens.contentMax) else Modifier)
                        .fillMaxWidth()
                        .fillMaxHeight(),
                )
            }
        }
    }
}

/**
 * [ModalNavigationDrawer] driven by [open], reporting user dismissals (scrim tap, swipe, back) through
 * [onOpenChange]. Gestures are off when not [enabled] (unless it is open, so it can still be swiped shut).
 * Used in both layouts so [content] never moves in the tree; on wide it stays closed with an empty sheet.
 */
@Composable
private fun ShellDrawer(
    open: Boolean,
    enabled: Boolean,
    onOpenChange: (Boolean) -> Unit,
    drawer: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val state = rememberDrawerState(DrawerValue.Closed)
    val slide = remember { tween<Float>(Motion.base, easing = Motion.easing) }
    LaunchedEffect(open) {
        // HANDOFF §4: slide 200 ms on the quiet curve. Material 1.4 takes open()/close() timing from its
        // MotionScheme, which is internal there, so the explicit-spec animateTo is the only way to set it.
        @Suppress("DEPRECATION")
        state.animateTo(if (open) DrawerValue.Open else DrawerValue.Closed, slide)
    }
    LaunchedEffect(state) {
        snapshotFlow { state.currentValue }.collect { onOpenChange(it == DrawerValue.Open) }
    }
    ModalNavigationDrawer(
        drawerContent = {
            ModalDrawerSheet(
                drawerState = state,
                drawerShape = RoundedCornerShape(0.dp),
                drawerContainerColor = MaterialTheme.tsukaima.surfaceRaised,
                drawerTonalElevation = 0.dp,
                windowInsets = WindowInsets(0),
                modifier = Modifier.width(Dimens.drawerWidth + 1.dp), // + the 1dp hairline
            ) { drawer() }
        },
        drawerState = state,
        gesturesEnabled = enabled || state.isOpen,
        scrimColor = MaterialTheme.tsukaima.scrim,
        content = content,
    )
}
