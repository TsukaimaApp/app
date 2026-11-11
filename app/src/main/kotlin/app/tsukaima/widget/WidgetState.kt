package app.tsukaima.widget

import app.tsukaima.data.gateway.Frame
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** What Hermes is doing in the chat the widget shows. */
enum class WidgetStatus { Idle, Working, Approval }

/**
 * The home-screen widget's content: the last chat that did something ([title]), what Hermes is doing in it
 * ([status]) and one line about that ([detail]: the reply's first line, the running tool, or the command that
 * waits for approval). [connected] false: the app is not connected to Hermes, so the rest may be out of date.
 */
data class WidgetState(
    val status: WidgetStatus = WidgetStatus.Idle,
    val title: String? = null,
    val detail: String? = null,
    val connected: Boolean = false,
) {
    /** The line next to the dot. */
    val line: String
        get() = when {
            !connected -> "Not connected"
            status == WidgetStatus.Approval -> "Waiting for your approval"
            status == WidgetStatus.Working -> "Hermes is working…"
            else -> "Hermes awaits"
        }

    companion object {
        const val MAX_DETAIL = 140

        /**
         * [state] after gateway event [ev] of any chat this app has open ([titles]: live session id → title, kept by
         * the caller from `session.title`). A turn that starts is Working; its tools give the detail; its end is
         * Idle with the reply's first line. Events that say nothing about a turn leave [state] as it is.
         */
        fun onEvent(state: WidgetState, ev: Frame.Event, titles: Map<String, String>): WidgetState {
            val title = titles[ev.sessionId] ?: state.title
            return when (ev.type) {
                "message.start" -> state.copy(status = WidgetStatus.Working, title = title, detail = null)
                "tool.start" -> state.copy(
                    status = WidgetStatus.Working, title = title,
                    detail = listOfNotNull(ev.payload.text("name"), ev.payload.text("context")).joinToString(" · ").line(),
                )
                "message.complete" -> state.copy(
                    status = WidgetStatus.Idle, title = title,
                    detail = ev.payload.text("text")?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()?.line(),
                )
                "error" -> state.copy(status = WidgetStatus.Idle, title = title)
                else -> state
            }
        }

        /** [state] when Hermes asks for an approval ([command]) in the chat titled [title]. */
        fun onApproval(state: WidgetState, title: String?, command: String?): WidgetState =
            state.copy(status = WidgetStatus.Approval, title = title ?: state.title, detail = command?.line())

        /** [state] once that approval was answered or withdrawn: the turn goes on. */
        fun onApprovalClosed(state: WidgetState): WidgetState =
            if (state.status == WidgetStatus.Approval) state.copy(status = WidgetStatus.Working, detail = null) else state

        private fun String.line(): String? =
            replace(Regex("\\s+"), " ").trim().takeIf { it.isNotEmpty() }?.let { if (it.length > MAX_DETAIL) it.take(MAX_DETAIL - 1) + "…" else it }

        private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }
}
