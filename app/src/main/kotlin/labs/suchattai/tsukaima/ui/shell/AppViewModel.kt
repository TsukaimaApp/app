package labs.suchattai.tsukaima.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import labs.suchattai.tsukaima.data.HermesState
import labs.suchattai.tsukaima.data.prefs.Settings
import labs.suchattai.tsukaima.di.AppContainer

/**
 * Shell state: settings, server state, the phone drawer, and auth routing. Read-only with respect to the
 * connection: [labs.suchattai.tsukaima.data.ConnectionManager] is started and stopped by the process lifecycle
 * (see `TsukaimaApp`), never here, because this ViewModel outlives activity restarts but not the process.
 */
class AppViewModel(container: AppContainer) : ViewModel() {
    /** Null until DataStore has loaded; the shell renders only the page background until then. */
    val settings: StateFlow<Settings?> = container.settings

    val hermes: StateFlow<HermesState> = container.connection.hermes

    private val _drawerOpen = MutableStateFlow(false)

    /** Phone overlay drawer requested open. Ignored by the wide layout, where the pane is always shown. */
    val drawerOpen: StateFlow<Boolean> = _drawerOpen.asStateFlow()

    fun openDrawer() {
        _drawerOpen.value = true
    }

    fun closeDrawer() {
        _drawerOpen.value = false
    }

    private val _authPrompts = Channel<Unit>(Channel.CONFLATED)

    /**
     * One event per false→true edge of `ConnectionManager.authRequired`. Buffered here (not collected from the
     * StateFlow in the UI) so an activity recreation neither drops the event nor routes to Settings twice.
     */
    val authPrompts: Flow<Unit> = _authPrompts.receiveAsFlow()

    init {
        viewModelScope.launch {
            container.connection.authRequired.filter { it }.collect { _authPrompts.trySend(Unit) }
        }
    }
}
