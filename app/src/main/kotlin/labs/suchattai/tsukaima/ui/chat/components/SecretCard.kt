package labs.suchattai.tsukaima.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import labs.suchattai.tsukaima.domain.chat.ChatItem
import labs.suchattai.tsukaima.domain.chat.SecretKind
import labs.suchattai.tsukaima.ui.components.GhostButton
import labs.suchattai.tsukaima.ui.components.IconButton44
import labs.suchattai.tsukaima.ui.components.IconName
import labs.suchattai.tsukaima.ui.components.PrimaryButton
import labs.suchattai.tsukaima.ui.components.TsukaimaTextField
import labs.suchattai.tsukaima.ui.theme.TsukaimaShapes
import labs.suchattai.tsukaima.ui.theme.TsukaimaType
import labs.suchattai.tsukaima.ui.theme.tsukaima

/** Card title per kind of masked prompt. */
internal fun SecretKind.title(): String = when (this) {
    SecretKind.Sudo -> "Hermes needs your sudo password"
    SecretKind.Secret -> "Hermes needs a secret"
    SecretKind.VaultUnlock -> "Unlock your password manager"
}

/**
 * Inline masked-value request (`sudo`, `secret`, `vault.unlock_prompt`; same tier as [ApprovalCard]). Shows what the
 * value is for (the sudo command or env var in a code well, the password manager's name as text; all
 * agent-controlled, so revealed through [CommandText.reveal]) and a masked field with a show/hide toggle. Send
 * hands the value to [onSend] and clears the field; Cancel declines ([onCancel], answered with `""`).
 *
 * The value lives only in this composition (`remember`, not saveable: it never reaches the saved-state bundle) and
 * is gone once sent. An answered card shows only "Sent" or "Declined".
 */
@Composable
fun SecretCard(
    item: ChatItem.SecretPrompt,
    onSend: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.tsukaima
    FloatingCard(modifier) {
        CardHeader(IconName.Key, item.kind.title())
        val subject = remember(item.subject) { CommandText.reveal(item.subject) }
        val prompt = remember(item.prompt) { item.prompt?.let { CommandText.reveal(it).text } }
        if (prompt != null) {
            Text(prompt, style = TsukaimaType.ui, color = colors.ink1, modifier = Modifier.padding(bottom = 8.dp))
        }
        if (item.subject.isNotEmpty()) {
            when (item.kind) {
                SecretKind.VaultUnlock -> Text(subject.text, style = TsukaimaType.ui, color = colors.ink2)
                else -> {
                    Text(
                        text = if (item.kind == SecretKind.Sudo) "COMMAND" else "ENVIRONMENT VARIABLE",
                        style = TsukaimaType.label,
                        color = colors.ink3,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                    Text(
                        text = subject.text,
                        style = TsukaimaType.mono,
                        color = colors.codeInk,
                        maxLines = 6,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(colors.codeBg, TsukaimaShapes.code)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            }
            if (subject.hadHiddenChars) {
                Text(
                    text = "This contains hidden characters.",
                    style = TsukaimaType.caption,
                    color = colors.warn,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        var value by remember(item.id) { mutableStateOf("") }
        var visible by remember(item.id) { mutableStateOf(false) }
        val send = {
            val v = value
            value = ""
            visible = false
            onSend(v)
        }
        if (!item.answered) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TsukaimaTextField(
                    value = value,
                    onValueChange = { value = it },
                    placeholder = if (item.kind == SecretKind.Secret) "Value" else "Password",
                    mono = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Send,
                    ),
                    keyboardActions = KeyboardActions(onSend = { if (value.isNotEmpty()) send() }),
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    modifier = Modifier.weight(1f),
                )
                IconButton44(
                    if (visible) IconName.VisibilityOff else IconName.Visibility,
                    if (visible) "Hide" else "Show",
                    onClick = { visible = !visible },
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
        ) {
            if (item.answered) {
                Text(
                    text = if (item.declined) "Declined" else "Sent",
                    style = TsukaimaType.caption,
                    color = colors.ink3,
                    modifier = Modifier.weight(1f),
                )
            }
            GhostButton("Cancel", onCancel, enabled = !item.answered)
            PrimaryButton("Send", send, icon = IconName.Check, enabled = !item.answered && value.isNotEmpty())
        }
    }
}

@Composable
private fun SecretPreview(dark: Boolean) = PreviewPage(dark) {
    SecretCard(ChatItem.SecretPrompt("srq-1", SecretKind.Sudo, "sudo apt-get install -y ripgrep", null, answered = false), {}, {})
    SecretCard(
        ChatItem.SecretPrompt("srq-2", SecretKind.Secret, "OPENWEATHER_API_KEY", "The weather skill needs an API key.", answered = false),
        {}, {},
    )
    SecretCard(ChatItem.SecretPrompt("srq-3", SecretKind.VaultUnlock, "1Password", null, answered = true), {}, {})
    SecretCard(ChatItem.SecretPrompt("srq-4", SecretKind.Sudo, "sudo -v", null, answered = true, declined = true), {}, {})
}

@Preview(name = "SecretCard light", heightDp = 1100)
@Composable
private fun SecretLightPreview() = SecretPreview(dark = false)

@Preview(name = "SecretCard dark", heightDp = 1100)
@Composable
private fun SecretDarkPreview() = SecretPreview(dark = true)
