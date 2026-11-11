package app.tsukaima.widget

import android.content.Context
import app.tsukaima.data.gateway.ConnectionState
import app.tsukaima.data.gateway.Frame
import app.tsukaima.data.prefs.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Keeps the widget's [state] from what the app already hears from Hermes (the gateway's events and requests of the
 * chats it has open, and the connection's state) and redraws the widget when it changes. It asks Hermes nothing.
 * The widget is only as fresh as the app's connection: once Android stops the app it shows what was last known.
 */
class WidgetUpdater(
    private val context: Context,
    private val scope: CoroutineScope,
    private val events: Flow<Frame.Event>,
    private val requests: Flow<Frame.ServerRequest>,
    private val connection: StateFlow<ConnectionState>,
    private val settings: StateFlow<Settings?>,
) {
    private val _state = MutableStateFlow(WidgetState())
    val state: StateFlow<WidgetState> = _state.asStateFlow()
    private val titles = LinkedHashMap<String, String>()
    private val approvals = LinkedHashSet<String>()

    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch { connection.collect { c -> _state.update { it.copy(connected = c == ConnectionState.Connected) } } }
        scope.launch {
            events.collect { ev ->
                when (ev.type) {
                    "session.title" -> (ev.payload["title"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { title ->
                        if (titles.size >= KEPT) titles.remove(titles.keys.first())
                        titles[ev.sessionId] = title
                    }
                    "request.cancel", "approval.cancelled" -> closeApprovals()
                }
                _state.update { WidgetState.onEvent(it, ev, titles) }
            }
        }
        scope.launch {
            requests.collect { req ->
                if (req.method != "approval" || !approvals.add(req.id)) return@collect
                val session = (req.params["session_id"] as? JsonPrimitive)?.contentOrNull
                val command = (req.params["command"] as? JsonPrimitive)?.contentOrNull
                _state.update { WidgetState.onApproval(it, titles[session], command) }
            }
        }
        // Redrawn at most a few times a second: a streaming turn changes nothing the widget shows.
        scope.launch {
            combine(_state, settings.map { it?.appLock == true }.distinctUntilChanged()) { s, private -> s to private }
                .distinctUntilChanged()
                .debounce(REDRAW_MS)
                .collect { (s, private) -> StatusWidget.render(context, s, private) }
        }
    }

    /** An approval was answered here or withdrawn by the server. */
    fun closeApprovals() {
        approvals.clear()
        _state.update(WidgetState::onApprovalClosed)
    }

    private companion object {
        const val KEPT = 32
        const val REDRAW_MS = 300L
    }
}
