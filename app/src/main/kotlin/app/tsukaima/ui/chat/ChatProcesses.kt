package app.tsukaima.ui.chat

import app.tsukaima.data.gateway.Frame
import app.tsukaima.data.gateway.GatewaySession
import app.tsukaima.data.gateway.ProcessEntry
import app.tsukaima.domain.chat.turnLive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The commands the agent left running in the background (`terminal` with `background: true`), for
 * [ChatViewModel]: which ones run (`process.list`), their output as it comes (`agent.terminal.output`), and Stop on
 * one (`process.kill`). They outlive the turn that started them, so the list is read when the chat gets its
 * session, after every turn, on the server's "Background Process Finished" line, and every [POLL_MS] while the
 * sheet is open. Hermes sends no event when a process starts or ends.
 */
internal class ChatProcesses(
    private val session: GatewaySession,
    private val backend: ChatBackend,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<ChatUiState>,
) {
    private var poll: Job? = null
    private var reading: Job? = null

    /** Starts watching the chat: call once. */
    fun start() {
        // A session bound, and each time a turn ends: the turn may have started or ended something.
        scope.launch {
            state.map { s -> s.chat.liveSessionId.takeIf { !s.offline && !s.loading } to s.chat.status.turnLive }
                .distinctUntilChanged()
                .collect { (live, turnLive) -> if (live != null && !turnLive) refresh() else if (live == null) clear() }
        }
    }

    /** A live event of this chat's session, as it arrives. */
    fun onEvent(ev: Frame.Event) {
        if (ev.sessionId != state.value.chat.liveSessionId) return
        when (ev.type) {
            OUTPUT -> {
                val id = ev.payload.text("process_id") ?: return
                val chunk = ev.payload.text("chunk").orEmpty()
                if (state.value.processes.none { it.id == id }) return refresh() // started since the last read
                state.update { s ->
                    s.copy(processes = s.processes.map { if (it.id == id) it.copy(tail = (it.tail + chunk).takeLast(MAX_TAIL)) else it })
                }
            }
            // "Background Process Finished: …" and its kin: something changed.
            STATUS -> if (ev.payload.text("kind") == PROCESS_KIND) refresh()
            CLOSE -> refresh()
        }
    }

    fun openSheet() {
        state.update { it.copy(processSheet = true, menu = null) }
        poll?.cancel()
        poll = scope.launch {
            while (true) {
                refresh()
                delay(POLL_MS)
            }
        }
    }

    fun closeSheet() {
        poll?.cancel()
        poll = null
        state.update { it.copy(processSheet = false) }
    }

    /** Stop on one process. The others, and a running turn, go on. */
    fun stop(id: String) {
        val s = state.value
        if (s.offline) return backend.toast("Not connected to Hermes")
        val live = s.chat.liveSessionId ?: return
        mark(id, stopping = true)
        scope.launch {
            try {
                when (session.processKill(live, id)) {
                    KILLED -> backend.toast("Stopped")
                    ALREADY_EXITED -> backend.toast("It had already finished")
                    else -> backend.toast("Couldn’t stop it")
                }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                backend.toast("Couldn’t stop it: ${e.message ?: "gateway error"}")
            } finally {
                mark(id, stopping = false)
            }
            refresh()
        }
    }

    private fun mark(id: String, stopping: Boolean) =
        state.update { s -> s.copy(processes = s.processes.map { if (it.id == id) it.copy(stopping = stopping) else it }) }

    private fun clear() {
        if (state.value.processes.isNotEmpty()) state.update { it.copy(processes = emptyList()) }
    }

    /** Reads the list; a read already in flight serves. A failure keeps what is shown. */
    private fun refresh() {
        if (reading?.isActive == true) return
        val live = state.value.chat.liveSessionId ?: return
        if (state.value.offline) return
        reading = scope.launch {
            try {
                val rows = rows(session.processList(live), WallClock.now(), state.value.processes)
                state.update { if (it.chat.liveSessionId == live) it.copy(processes = rows) else it }
            } catch (c: CancellationException) {
                throw c
            } catch (_: Exception) {
                // An older Hermes without the method, or a dropped socket: the chip just does not show.
            }
        }
    }

    companion object {
        const val POLL_MS = 3_000L
        const val MAX_TAIL = 8_000
        private const val OUTPUT = "agent.terminal.output"
        private const val CLOSE = "terminal.close"
        private const val STATUS = "status.update"
        const val PROCESS_KIND = "process"
        private const val KILLED = "killed"
        private const val ALREADY_EXITED = "already_exited"
        private const val RUNNING = "running"

        /**
         * `process.list` rows as the sheet's, read at [now]: running ones first, each group oldest first. A row
         * keeps the output the phone already has for it when that is longer than the server's tail, and its
         * "stopping" mark.
         */
        fun rows(entries: List<ProcessEntry>, now: Long, shown: List<ProcessRow> = emptyList()): List<ProcessRow> {
            val before = shown.associateBy { it.id }
            return entries.map { e ->
                val old = before[e.id]
                val tail = e.outputTail.orEmpty().takeLast(MAX_TAIL)
                ProcessRow(
                    id = e.id,
                    command = e.command,
                    running = e.status == RUNNING,
                    since = e.uptimeSeconds?.let { now - it * 1000 },
                    tail = if (old != null && old.tail.length > tail.length && old.tail.endsWith(tail)) old.tail else tail,
                    exitCode = e.exitCode,
                    stopping = old?.stopping == true && e.status == RUNNING,
                )
            }.sortedWith(compareBy<ProcessRow> { !it.running }.thenByDescending { now - (it.since ?: now) })
        }

        private fun kotlinx.serialization.json.JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    }
}
