package app.tsukaima.domain.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** One slash command the composer offers: [name] with its slash (`/goal`), a one-line [description]; [skill] for a skill's own command. */
data class SlashCommand(val name: String, val description: String, val skill: Boolean)

/**
 * The server's slash commands (`commands.catalog`): [offered] is what the composer suggests, in the server's order;
 * [known] every name the server accepts (aliases, skills and plugin commands included), which decides whether a
 * typed `/word` is run as a command or sent to the model as text.
 */
data class SlashCatalog(val offered: List<SlashCommand>, val known: Set<String>) {
    /**
     * The commands to suggest for composer text [input]: only while it is one `/word` being typed (no space yet).
     * Names starting with it first, then names containing it; at most [MAX_MATCHES].
     */
    fun matches(input: String): List<SlashCommand> {
        if (!input.startsWith("/") || input.any(Char::isWhitespace)) return emptyList()
        val typed = input.lowercase()
        val (starts, rest) = offered.partition { it.name.startsWith(typed) }
        val contains = if (typed.length > 1) rest.filter { typed.drop(1) in it.name } else emptyList()
        return (starts + contains).take(MAX_MATCHES)
    }

    /** Whether [text] is a command the server knows (its first word, case-insensitive). */
    fun isCommand(text: String): Boolean = Slash.split(text)?.first in known

    companion object {
        const val MAX_MATCHES = 40

        /**
         * `commands.catalog` → catalog. `pairs` is `[name, description]` for every command and skill; `canon` maps
         * each accepted name (aliases too) to its command; `skills` and `plugin_commands` are keyed by name;
         * `commands[name].desktop` says where a graphical client shows it. Not offered: [HIDDEN_PLACES], and
         * [Slash.NATIVE] (the app has its own control, and the command would act on the server's command worker,
         * not on this chat).
         */
        fun parse(result: JsonObject): SlashCatalog {
            fun keys(name: String) = (result[name] as? JsonObject)?.keys.orEmpty()
            val meta = result["commands"] as? JsonObject
            val skills = keys("skills")
            fun hidden(name: String) = name in Slash.NATIVE ||
                ((meta?.get(name) as? JsonObject)?.get("desktop") as? JsonPrimitive)?.contentOrNull in HIDDEN_PLACES
            val offered = (result["pairs"] as? JsonArray).orEmpty().mapNotNull { pair ->
                val cells = (pair as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull.orEmpty() } ?: return@mapNotNull null
                val name = cells.getOrNull(0)?.lowercase()?.takeIf { it.startsWith("/") && it.length > 1 } ?: return@mapNotNull null
                if (hidden(name)) null else SlashCommand(name, cells.getOrNull(1).orEmpty(), skill = name in skills)
            }.distinctBy { it.name }
            val plugins = keys("plugin_commands").map { "/" + it.removePrefix("/") }
            val known = (offered.map { it.name } + keys("canon") + skills + plugins).mapTo(HashSet()) { it.lowercase() }
            return SlashCatalog(offered, known)
        }

        /** `desktop` values of commands that only make sense in a terminal, or that a client replaces with its own UI. */
        private val HIDDEN_PLACES = setOf("terminal", "hidden", "settings", "composer-voice")
    }
}

object Slash {
    /** Commands the app does itself (drawer, session list): typed anyway, they are refused with a hint. */
    val NATIVE = setOf("/new", "/reset", "/resume", "/sessions", "/history", "/quit", "/exit")

    /** Commands that change the stored transcript on the server: the chat is read again after them. */
    val REWRITES_HISTORY = setOf("/undo", "/rewind", "/retry", "/rollback", "/compress", "/compact")

    /** `/Goal  ship it` → (`/goal`, `ship it`); null when [text] does not start with a `/word`. */
    fun split(text: String): Pair<String, String>? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("/") || trimmed.length < 2 || trimmed[1].isWhitespace()) return null
        val name = trimmed.substringBefore(' ').substringBefore('\n').lowercase()
        // A path (`/etc/hosts`) is not a command.
        if ('/' in name.drop(1)) return null
        return name to trimmed.drop(name.length).trim()
    }

    private val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")

    /** A command's output as plain text: terminal colour codes removed, blank edges trimmed. */
    fun clean(output: String): String = ANSI.replace(output, "").trim('\n', '\r').trimEnd()
}

/**
 * What the server answered to a slash command (`slash.exec`, or `command.dispatch` after its 4018): text to show
 * ([Output]), a prompt to send as a turn ([Send]; the bubble shows [Send.shown]), text for the composer ([Prefill]),
 * or another command to run instead ([Alias]).
 */
sealed interface SlashResult {
    data class Output(val text: String) : SlashResult
    data class Send(val prompt: String, val shown: String?, val notice: String?) : SlashResult
    data class Prefill(val text: String) : SlashResult
    data class Alias(val target: String) : SlashResult

    companion object {
        /** The directive in [result]: its `type` (`exec`, `plugin`, `send`, `skill`, `prefill`, `alias`; none = plain output). */
        fun of(result: JsonObject): SlashResult {
            fun text(key: String) = (result[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            val message = text("message")
            return when (text("type")) {
                "send", "skill" -> if (message != null) Send(message, text("display"), text("notice")) else Output(text("notice") ?: NO_OUTPUT)
                "prefill" -> Prefill(message.orEmpty())
                "alias" -> text("target")?.let(::Alias) ?: Output(NO_OUTPUT)
                else -> Output(listOfNotNull(text("output")?.let(Slash::clean), text("warning"), text("notice")).joinToString("\n\n").ifBlank { NO_OUTPUT })
            }
        }

        const val NO_OUTPUT = "(no output)"
    }
}
