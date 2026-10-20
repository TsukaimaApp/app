package labs.suchattai.tsukaima.domain.chat

/** Colour family of the status-line dot (mapped to theme colours by the UI). */
enum class StatusTone { Grey, Accent, Amber, Red }

/** What the agent is doing right now, as shown in the chat status line. */
sealed interface AgentStatus {
    val label: String
    val tone: StatusTone
    val pulsing: Boolean get() = false

    data object Idle : AgentStatus {
        override val label = "Hermes is listening"
        override val tone = StatusTone.Grey
    }

    data class Thinking(val flair: String) : AgentStatus {
        override val label get() = flair
        override val tone get() = StatusTone.Accent
        override val pulsing get() = true
    }

    data class Tool(val name: String) : AgentStatus {
        override val label get() = "Running $name"
        override val tone get() = StatusTone.Accent
        override val pulsing get() = true
    }

    data object Replying : AgentStatus {
        override val label = "Replying…"
        override val tone = StatusTone.Accent
        override val pulsing = true
    }

    data object Approval : AgentStatus {
        override val label = "Waiting for your approval"
        override val tone = StatusTone.Amber
    }

    /** A `clarify` request waits for the user. */
    data object Question : AgentStatus {
        override val label = "Waiting for your answer"
        override val tone = StatusTone.Amber
    }

    /** A `sudo` / `vault.unlock_prompt` (password) or `secret` request waits for the user. */
    data class Secret(val password: Boolean) : AgentStatus {
        override val label get() = if (password) "Waiting for a password" else "Waiting for a secret"
        override val tone get() = StatusTone.Amber
    }

    /** `session.compress` is summarizing the conversation (not a turn: Stop does not apply). */
    data object Compacting : AgentStatus {
        override val label = "Compacting context…"
        override val tone = StatusTone.Accent
        override val pulsing = true
    }

    data object Stopped : AgentStatus {
        override val label = "Gateway stopped"
        override val tone = StatusTone.Red
    }

    data object Offline : AgentStatus {
        override val label = "Can’t reach Hermes"
        override val tone = StatusTone.Red
    }
}

/** The agent is working on a turn (what Stop interrupts): thinking, running a tool or streaming a reply. */
val AgentStatus.running: Boolean
    get() = this is AgentStatus.Thinking || this is AgentStatus.Tool || this == AgentStatus.Replying
