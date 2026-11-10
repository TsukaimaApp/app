package app.tsukaima.ui.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import app.tsukaima.data.gateway.GatewaySession
import app.tsukaima.domain.chat.ChatReducer
import app.tsukaima.domain.chat.Slash
import app.tsukaima.domain.chat.SlashCatalog
import app.tsukaima.domain.chat.SlashResult

/**
 * Slash commands for [ChatViewModel]. The server's catalog (`commands.catalog`) is read the first time the composer
 * starts with `/`; the composer then suggests commands, and a sent text whose first word the catalog knows is run on
 * the server (`slash.exec`, else `command.dispatch`) instead of going to the model. What comes back is shown as a
 * note in the transcript, sent as a turn (a skill, `/plan`, `/retry`: the bubble shows the command, the server's
 * expansion is what is submitted), or put into the composer. Text the catalog does not know (`/etc/hosts is empty`)
 * stays a message, and so does everything while the catalog is not loaded.
 */
internal class ChatCommands(
    private val session: GatewaySession,
    private val backend: ChatBackend,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<ChatUiState>,
    private val reload: suspend (live: String) -> Unit,
    private val send: (live: String, shown: String, prompt: String) -> Unit,
) {
    private var loading: Job? = null
    private var counter = 0

    /** Reads the catalog once a live session exists; a failed read is tried again the next time `/` is typed. */
    fun load() {
        if (state.value.commands != null || loading?.isActive == true) return
        val live = state.value.chat.liveSessionId ?: return
        loading = scope.launch {
            try {
                val catalog = SlashCatalog.parse(session.commandsCatalog(live))
                state.update { it.copy(commands = catalog) }
            } catch (c: CancellationException) {
                throw c
            } catch (_: Exception) {
                // No suggestions, and slash text goes to the model as before.
            }
        }
    }

    /**
     * Runs [text] as a slash command when the catalog knows its first word; false when it is not one (the caller
     * sends it as a message). True means it was taken: the composer is cleared and a note waits for the answer.
     */
    fun run(live: String, text: String): Boolean {
        val catalog = state.value.commands ?: return false
        val (name, _) = Slash.split(text) ?: return false
        if (name !in catalog.known) return false
        if (name in Slash.NATIVE) {
            backend.toast("$name works from the app’s own menus")
            return true
        }
        val id = "cmd-${++counter}"
        val command = text.trim()
        state.update { it.copy(input = "", chat = ChatReducer.onCommandStarted(it.chat, id, command)) }
        scope.launch { execute(live, id, command, aliased = false) }
        return true
    }

    private suspend fun execute(live: String, id: String, command: String, aliased: Boolean) {
        val (name, arg) = Slash.split(command) ?: return finish(id, "Not a command", failed = true)
        val result = try {
            SlashResult.of(session.slash(live, command, name, arg))
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            return finish(id, e.message ?: "gateway error", failed = true)
        }
        // The command may have cut or rewritten the stored transcript (/undo, /retry, /compress).
        if (name in Slash.REWRITES_HISTORY && state.value.chat.liveSessionId == live) reload(live)
        when (result) {
            is SlashResult.Output -> finish(id, result.text, failed = false)
            is SlashResult.Alias ->
                if (aliased) finish(id, "Unknown command", failed = true)
                else execute(live, id, listOf(result.target.let { if (it.startsWith("/")) it else "/$it" }, arg).filter { it.isNotEmpty() }.joinToString(" "), aliased = true)
            is SlashResult.Prefill -> {
                state.update { it.copy(chat = ChatReducer.onCommandDropped(it.chat, id), input = it.input.ifEmpty { result.text }) }
            }
            is SlashResult.Send -> {
                state.update { it.copy(chat = ChatReducer.onCommandDropped(it.chat, id)) }
                result.notice?.let(backend::toast)
                if (state.value.chat.liveSessionId == live) send(live, result.shown ?: command, result.prompt)
            }
        }
    }

    private fun finish(id: String, output: String, failed: Boolean) =
        state.update { it.copy(chat = ChatReducer.onCommandFinished(it.chat, id, output, failed)) }
}
