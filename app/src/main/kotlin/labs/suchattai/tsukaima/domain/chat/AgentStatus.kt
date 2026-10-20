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

    data object Stopped : AgentStatus {
        override val label = "Gateway stopped"
        override val tone = StatusTone.Red
    }

    data object Offline : AgentStatus {
        override val label = "Can’t reach Hermes"
        override val tone = StatusTone.Red
    }
}
