package app.tsukaima.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.tsukaima.domain.chat.ChatItem
import app.tsukaima.domain.chat.ConnectionAnswer
import app.tsukaima.domain.chat.ConnectionEnvField
import app.tsukaima.domain.chat.ConnectionTarget
import app.tsukaima.ui.chat.components.ConnectionCard
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionCardUiTest : ComposeUiTest() {

    private val deadline = System.currentTimeMillis() / 1000.0 + 280

    private fun card(vararg targets: ConnectionTarget, seq: Long = 1, envSentAt: Map<String, Long> = emptyMap(), suggested: List<String> = emptyList()) =
        ChatItem.ConnectionRequest(
            "conn-1", "1", null, deadline, targets.toList(), settled = false, settledBy = null,
            seq = seq, suggested = suggested, envSentAt = envSentAt,
        )

    private val install = ConnectionTarget(
        "filesystem", "mcp", "install", "pending", false,
        requiredEnv = listOf(ConnectionEnvField("ROOT", required = true, secret = false, default = "", prompt = "Root folder")),
    )

    private fun fieldText(): String =
        compose.onNode(hasSetTextAction()).fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    @Test
    fun typedEnvIsClearedAfterConnect() {
        val answers = mutableListOf<ConnectionAnswer>()
        setThemed { ConnectionCard(card(install), onAnswer = { answers += it }, onOpen = {}, onSkipAll = {}, onContinue = {}) }

        compose.onNodeWithText("Connect").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("/srv/data")
        assertEquals("/srv/data", fieldText())
        compose.onNodeWithText("Connect").assertIsEnabled().performClick()
        compose.waitForIdle()

        assertEquals(mapOf("ROOT" to "/srv/data"), answers.single().targets.single().env)
        // Sent: the value is gone from the card at once (the row stays open until the server's frame moves it).
        assertEquals("", fieldText())
        compose.onNodeWithText("Connect").assertIsNotEnabled()
    }

    @Test
    fun suggestedCardShowsAHint() {
        var continued = 0
        val suggested = card(suggested = listOf("linear", "github", "notion", "slack", "jira", "drive", "gmail"))
        setThemed { ConnectionCard(suggested, onAnswer = {}, onOpen = {}, onSkipAll = {}, onContinue = { continued++ }) }

        compose.onNodeWithText("Hermes suggests connecting: linear, github, notion, slack, jira and 2 more").assertIsDisplayed()
        compose.onNode(hasText("Skip all")).assertDoesNotExist()
        compose.onNodeWithText("Continue").assertIsDisplayed().performClick()
        assertEquals(1, continued)
    }

    @Test
    fun anAnswerInFlightDisablesTheCard() {
        val linear = ConnectionTarget("linear", "connector", "connect", "pending", false)
        setThemed { ConnectionCard(card(linear, install), onAnswer = {}, onOpen = {}, onSkipAll = {}, onContinue = {}, answering = true) }

        compose.onAllNodesWithText("Connecting…").fetchSemanticsNodes().let { assertEquals(2, it.size) }
        compose.onNode(hasText("Connect")).assertDoesNotExist()
        compose.onAllNodesWithText("Skip").fetchSemanticsNodes().size.let { assertEquals(2, it) }
        compose.onNodeWithText("Skip all").assertIsNotEnabled()
        compose.onNodeWithText("Continue").assertIsNotEnabled()
        compose.onAllNodesWithText("Connecting…")[0].assertIsNotEnabled()
    }

    @Test
    fun aLinkAfterTheEnvIsOfferedAsContinueInBrowser() {
        val both = install.copy(name = "notion", state = "initiated", connectUrl = "https://auth.example.test/notion")
        val opened = mutableListOf<String>()
        val answers = mutableListOf<ConnectionAnswer>()
        // Before the env went out, Connect sends it (it does not open the link).
        setThemed {
            ConnectionCard(card(both, seq = 2, envSentAt = mapOf("notion" to 1)), onAnswer = { answers += it }, onOpen = { opened += it }, onSkipAll = {}, onContinue = {})
        }

        compose.onNodeWithText("Continue in browser").assertIsDisplayed().performClick()
        assertEquals(listOf("notion"), opened)
        assertEquals(emptyList<ConnectionAnswer>(), answers)
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
    }

    @Test
    fun connectOnATargetWithLinkAndEnvSendsTheEnvFirst() {
        val both = install.copy(name = "notion", connectUrl = "https://auth.example.test/notion")
        val opened = mutableListOf<String>()
        val answers = mutableListOf<ConnectionAnswer>()
        setThemed { ConnectionCard(card(both), onAnswer = { answers += it }, onOpen = { opened += it }, onSkipAll = {}, onContinue = {}) }

        compose.onNodeWithText("Connect").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("/srv")
        compose.onNodeWithText("Connect").performClick()
        assertEquals(mapOf("ROOT" to "/srv"), answers.single().targets.single().env)
        assertEquals(emptyList<String>(), opened)
    }
}
