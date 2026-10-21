package app.tsukaima.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import app.tsukaima.domain.chat.ChatItem
import app.tsukaima.domain.chat.ConnectionAnswer
import app.tsukaima.domain.chat.ConnectionAnswers
import app.tsukaima.domain.chat.ConnectionEnvField
import app.tsukaima.domain.chat.ConnectionTarget
import app.tsukaima.ui.components.ButtonSize
import app.tsukaima.ui.components.GhostButton
import app.tsukaima.ui.components.IconName
import app.tsukaima.ui.components.PrimaryButton
import app.tsukaima.ui.components.SecondaryButton
import app.tsukaima.ui.components.TsukaimaIcon
import app.tsukaima.ui.components.TsukaimaTextField
import app.tsukaima.ui.theme.Dimens
import app.tsukaima.ui.theme.TsukaimaShapes
import app.tsukaima.ui.theme.TsukaimaType
import app.tsukaima.ui.theme.tsukaima

/**
 * A connection operation (`connection.request`, same tier as [ApprovalCard]): "Hermes wants to connect" with one row
 * per target (its name, helper text and state chip) and the server's deadline counting down.
 *
 * An unresolved row offers "Connect" and "Skip". A row with `required_env` fields (masked when secret, prefilled
 * with the default; a blank optional one is left out) approves ([onAnswer]) with their values once every required
 * one is filled, even when it also has a link: the env goes first, and the link is offered ("Continue in browser",
 * [onOpen]) only when a later frame still shows the row open with it ([ChatItem.ConnectionRequest.continuesInBrowser]).
 * A row with only a link opens it ([onOpen]); a bare row approves. The footer offers "Skip all" and "Continue" (end
 * the operation now). A display operation (no rows) shows a "Hermes suggests connecting: …" line and Continue.
 *
 * While an answer is in flight ([answering]) every button is off and Connect reads "Connecting…"; at or past the
 * deadline every button is off. Rows move only with the server's frames; once settled the card collapses to one
 * line. Field values live only in this composition (`remember`, never the saved-state bundle) and are dropped as
 * soon as Connect sends them. Agent-reachable text is revealed through [CommandText.reveal].
 */
@Composable
fun ConnectionCard(
    item: ChatItem.ConnectionRequest,
    onAnswer: (ConnectionAnswer) -> Unit,
    onOpen: (name: String) -> Unit,
    onSkipAll: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
    answering: Boolean = false,
    nowSeconds: () -> Double = { System.currentTimeMillis() / 1000.0 },
) {
    val colors = MaterialTheme.tsukaima
    if (item.settled) {
        val connected = item.targets.any { it.state == "connected" }
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.gutter, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TsukaimaIcon(IconName.Link, contentDescription = null, size = 18.dp, tint = colors.ink3)
            Text(if (connected) "Connected" else "Skipped", style = TsukaimaType.caption, color = colors.ink2)
        }
        return
    }
    val now by produceState(nowSeconds(), item.deadlineAt) {
        while (true) {
            value = nowSeconds()
            delay(1_000)
        }
    }
    val countdown = ConnectionAnswers.countdown(item.deadlineAt, now)
    val active = countdown != "Expired" && !answering
    val hint = remember(item.targets.isEmpty(), item.suggested) {
        if (item.targets.isEmpty()) ConnectionAnswers.suggestionHint(item.suggested) { CommandText.reveal(it).text } else null
    }
    FloatingCard(modifier) {
        CardHeader(IconName.Link, "Hermes wants to connect")
        hint?.let { Text(it, style = TsukaimaType.caption, color = colors.ink2) }
        item.targets.forEachIndexed { index, target ->
            TargetRow(
                item.id, target, active, answering, item.continuesInBrowser(target), onAnswer, onOpen,
                Modifier.padding(top = if (index == 0) 0.dp else 12.dp),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
        ) {
            Text(countdown.orEmpty(), style = TsukaimaType.caption, color = colors.ink3, modifier = Modifier.weight(1f))
            if (item.targets.any { !it.resolved }) GhostButton("Skip all", onSkipAll, enabled = active)
            SecondaryButton("Continue", onContinue, enabled = active)
        }
    }
}

@Composable
private fun TargetRow(
    cardId: String,
    target: ConnectionTarget,
    active: Boolean,
    answering: Boolean,
    inBrowser: Boolean,
    onAnswer: (ConnectionAnswer) -> Unit,
    onOpen: (name: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.tsukaima
    val label = remember(target.label) { CommandText.reveal(target.label).text }
    val helpers = remember(target.description, target.detail, target.instructions) {
        listOfNotNull(target.description, target.detail, target.instructions).map { CommandText.reveal(it).text }
    }
    // What the user typed, keyed by card and target: it survives the operation's update frames (a field not typed
    // in shows its default, also one a later frame brought), never the saved state.
    val typed = remember(cardId, target.name) { mutableStateMapOf<String, String>() }
    fun value(field: ConnectionEnvField) = typed[field.name] ?: field.default
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = TsukaimaType.ui, color = colors.ink1, modifier = Modifier.weight(1f))
            StateChip(target.stateLabel, target.state == "connected")
        }
        helpers.forEach { Text(it, style = TsukaimaType.caption, color = colors.ink2, modifier = Modifier.padding(top = 2.dp)) }
        if (target.resolved) return@Column
        // The env went out and the server still wants the browser: only the link is left to do.
        if (!inBrowser) target.requiredEnv.forEach { field -> EnvField(field, value(field)) { typed[field.name] = it } }
        val ready = target.requiredEnv.all { !it.required || value(it).isNotEmpty() }
        val linkOnly = target.connectUrl != null && (inBrowser || target.requiredEnv.isEmpty())
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
        ) {
            GhostButton("Skip", { onAnswer(ConnectionAnswers.skip(target.name)) }, enabled = active, size = ButtonSize.Sm)
            PrimaryButton(
                when {
                    answering -> "Connecting…"
                    inBrowser -> "Continue in browser"
                    else -> "Connect"
                },
                {
                    when {
                        linkOnly -> onOpen(target.name)
                        target.requiredEnv.isNotEmpty() -> {
                            onAnswer(
                                ConnectionAnswers.approve(
                                    target.name,
                                    target.requiredEnv.filter { it.required || value(it).isNotBlank() }.associate { it.name to value(it) },
                                ),
                            )
                            // Sent: the typed values leave the composition at once.
                            typed.clear()
                        }
                        else -> onAnswer(ConnectionAnswers.approve(target.name))
                    }
                },
                icon = if (linkOnly) IconName.OpenInNew else null,
                enabled = active && (linkOnly || ready),
                size = ButtonSize.Sm,
            )
        }
    }
}

@Composable
private fun EnvField(field: ConnectionEnvField, value: String, onValueChange: (String) -> Unit) {
    TsukaimaTextField(
        value = value,
        onValueChange = onValueChange,
        label = field.prompt ?: field.name,
        mono = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (field.secret) KeyboardType.Password else KeyboardType.Text,
            autoCorrectEnabled = false,
        ),
        visualTransformation = if (field.secret) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    )
}

/** The target's state in a small pill; [ok] (connected) in the success colour. */
@Composable
private fun StateChip(text: String, ok: Boolean) {
    val colors = MaterialTheme.tsukaima
    Text(
        text = text,
        style = TsukaimaType.caption,
        color = if (ok) colors.ok else colors.ink2,
        modifier = Modifier
            .background(colors.surfaceSunken, TsukaimaShapes.pill)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun ConnectionPreview(dark: Boolean) = PreviewPage(dark) {
    val deadline = System.currentTimeMillis() / 1000.0 + 280
    ConnectionCard(
        ChatItem.ConnectionRequest(
            "conn-1", "1", null, deadline,
            listOf(
                ConnectionTarget("linear", "connector", "connect", "initiated", false, display = "Linear", connectUrl = "https://example.test"),
                ConnectionTarget(
                    "filesystem", "mcp", "install", "pending", false, description = "Read and write files under a folder.",
                    requiredEnv = listOf(
                        ConnectionEnvField("ROOT", required = true, secret = false, default = "/srv", prompt = "Root folder"),
                        ConnectionEnvField("TOKEN", required = true, secret = true, default = "", prompt = null),
                    ),
                ),
                ConnectionTarget("github", "connector", "connect", "connected", true, display = "GitHub"),
            ),
            settled = false, settledBy = null,
        ),
        {}, {}, {}, {},
    )
    ConnectionCard(
        ChatItem.ConnectionRequest(
            "conn-2", "2", null, deadline,
            listOf(ConnectionTarget("linear", "connector", "connect", "skipped", true)), settled = true, settledBy = "all_resolved",
        ),
        {}, {}, {}, {},
    )
}

@Preview(name = "ConnectionCard light", heightDp = 900)
@Composable
private fun ConnectionLightPreview() = ConnectionPreview(dark = false)

@Preview(name = "ConnectionCard dark", heightDp = 900)
@Composable
private fun ConnectionDarkPreview() = ConnectionPreview(dark = true)
