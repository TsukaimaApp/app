package app.tsukaima.data.gateway

import java.util.concurrent.ConcurrentHashMap

/**
 * The gateway event types this app version knows of: every type in the Hermes v0.23.1 contract
 * (`tui_gateway/contracts`, 72 declared by name, plus the `subagent.*` family and the legacy `turn.error`).
 * Most are handled or deliberately ignored (desktop panes, server-side voice, the mascot). A type outside this list
 * came from a newer Hermes: it is reported once per process, so the gap reaches a problem report's log.
 */
object KnownEvents {
    val TYPES: Set<String> = setOf(
        "agent.print", "agent.terminal.output", "approval.cancelled", "background.complete",
        "billing.step_up.verification", "bot_relay.outbox.pending", "browser.progress", "btw.complete",
        "connection.request", "connection.update", "cron.changed", "display.install.done",
        "display.install.log", "display.lease", "display.status", "error", "free_tier.challenge",
        "gateway.ready", "message.complete", "message.delta", "message.interim", "message.reaction",
        "message.start", "moa.aggregating", "moa.phase", "moa.progress", "moa.reference", "notice",
        "notification.clear", "notification.show", "onboarding.ended", "pairing.changed", "pane.reveal",
        "pet.changed", "pet.generate.progress", "pet.hatch.progress", "platforms.changed", "preview.close",
        "preview.open", "preview.restart.complete", "preview.restart.progress", "projects.changed",
        "reaction", "reasoning.available", "reasoning.delta", "request.cancel", "review.summary",
        "session.control.update", "session.info", "session.reclaimed", "session.resume_progress",
        "session.title", "session.usage", "sessions.changed", "setup.ready", "skills.changed", "skin.changed",
        "status.update", "terminal.close", "thinking.delta", "tip.show", "todo.updated", "tool.complete",
        "tool.generating", "tool.output_risk", "tool.progress", "tool.start", "voice.interrupted",
        "voice.partial", "voice.status", "voice.transcript", "wake.detected",
        "turn.error",
    )

    private const val SUBAGENT_PREFIX = "subagent."
    private val reported = ConcurrentHashMap.newKeySet<String>()

    fun isKnown(type: String): Boolean = type in TYPES || type.startsWith(SUBAGENT_PREFIX)

    /** True the first time an unknown [type] is seen in this process. */
    fun isNew(type: String): Boolean = !isKnown(type) && reported.add(type)
}
