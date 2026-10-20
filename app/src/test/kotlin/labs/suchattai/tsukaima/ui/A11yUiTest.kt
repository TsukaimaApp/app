package labs.suchattai.tsukaima.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onRoot
import labs.suchattai.tsukaima.domain.chat.AgentStatus
import labs.suchattai.tsukaima.domain.chat.ChatItem
import labs.suchattai.tsukaima.domain.chat.ChatState
import labs.suchattai.tsukaima.ui.chat.ChatActions
import labs.suchattai.tsukaima.ui.chat.ChatContent
import labs.suchattai.tsukaima.ui.chat.ChatUiState
import org.junit.Assert.assertTrue
import org.junit.Test

class A11yUiTest : ComposeUiTest() {

    private val transcript = ChatState(
        items = listOf(
            ChatItem.UserMessage("u1", "Show me the backup unit."),
            ChatItem.AssistantMessage(
                "a1",
                "Here it is:\n\n```ini\n[Service]\nExecStart=/usr/local/bin/backup\n```\n\nWant me to restart it?",
                streaming = false,
                reasoning = null,
            ),
            ChatItem.Approval("srq-1", "req-1", "systemctl restart backup", "terminal", answered = false),
        ),
        status = AgentStatus.Approval,
        storedSessionId = "20261020_010916_48c967",
    )

    private fun SemanticsNode.labelled(): Boolean =
        !config.getOrNull(SemanticsProperties.ContentDescription).isNullOrEmpty() ||
            config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.isNotBlank() } ||
            children.any { it.labelled() }

    private fun SemanticsNode.unlabelledClickables(): List<SemanticsNode> =
        (if (SemanticsActions.OnClick in config && !labelled()) listOf(this) else emptyList()) +
            children.flatMap { it.unlabelledClickables() }

    @Test
    fun `every clickable on the chat screen has a description or text`() {
        setThemed {
            ChatContent(
                state = ChatUiState(chat = transcript, model = "Hermes 4 · 405B", isNew = false),
                showMenu = false,
                onDrawer = {},
                onStatus = {},
                actions = ChatActions(),
            )
        }

        val root = compose.onRoot(useUnmergedTree = true).fetchSemanticsNode()
        val clickables = generateSequence(listOf(root)) { level -> level.flatMap { it.children }.ifEmpty { null } }
            .flatten().count { SemanticsActions.OnClick in it.config }
        assertTrue("the transcript, top bar and composer render clickables ($clickables)", clickables >= 5)
        val bad = root.unlabelledClickables()
        assertTrue(
            "unlabelled clickables:\n" + bad.joinToString("\n") { "#${it.id} ${it.boundsInRoot} ${it.config}" },
            bad.isEmpty(),
        )
    }
}
