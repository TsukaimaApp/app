package app.tsukaima.ui.chat.components

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
import app.tsukaima.domain.chat.ChatItem
import app.tsukaima.domain.chat.SecretKind
import app.tsukaima.domain.chat.VaultAnswers
import app.tsukaima.ui.components.GhostButton
import app.tsukaima.ui.components.IconButton44
import app.tsukaima.ui.components.IconName
import app.tsukaima.ui.components.PrimaryButton
import app.tsukaima.ui.components.TsukaimaTextField
import app.tsukaima.ui.theme.TsukaimaShapes
import app.tsukaima.ui.theme.TsukaimaType
import app.tsukaima.ui.theme.tsukaima

/** Card title per kind of masked prompt. */
internal fun SecretKind.title(): String = when (this) {
    SecretKind.Sudo -> "Hermes needs your sudo password"
    SecretKind.Secret -> "Hermes needs a secret"
    SecretKind.VaultUnlock -> "Unlock your password manager"
    SecretKind.VaultCode -> "Hermes needs a code"
    SecretKind.VaultSaveLogin -> "Save this login?"
}

/**
 * Inline masked-value request (`sudo`, `secret`, `vault.*`; same tier as [ApprovalCard]). Shows what the value is
 * for (the sudo command or env var in a code well, the password manager's name or the site as text; all
 * agent-controlled, so revealed through [CommandText.reveal]) and the field(s) for it:
 *
 * - `sudo` / `secret` / `vault.unlock_prompt`: a masked field with a show/hide toggle; Send / Cancel.
 * - `vault.code`: a plain "Enter the code" field (number keyboard when the hint asks for digits) with the hint as
 *   helper text; Send / Decline.
 * - `vault.save_login`: the full origin under the site (so http vs https shows), "Username or email" and a masked
 *   "Password"; "Save login" (both filled) sends
 *   [VaultAnswers.saveLogin], "Don’t save" declines.
 *
 * Send hands the value to [onSend] and clears the fields; the secondary button declines ([onCancel], answered with
 * `""`). The values live only in this composition (`remember`, not saveable: they never reach the saved-state
 * bundle) and are gone once sent. An answered card shows only "Sent" or "Declined".
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
        // The save-login page's full origin, when it says more than the subject (the scheme: http vs https).
        val origin = remember(item.origin, item.subject) {
            item.origin?.takeIf { it.isNotBlank() && it != item.subject }?.let(CommandText::reveal)
        }
        if (prompt != null && item.kind != SecretKind.VaultCode) {
            Text(prompt, style = TsukaimaType.ui, color = colors.ink1, modifier = Modifier.padding(bottom = 8.dp))
        }
        if (item.subject.isNotEmpty()) {
            when (item.kind) {
                SecretKind.VaultUnlock, SecretKind.VaultCode, SecretKind.VaultSaveLogin ->
                    Text(subject.text, style = TsukaimaType.ui, color = colors.ink2)
                SecretKind.Sudo, SecretKind.Secret -> {
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
            if (origin != null) {
                Text(
                    text = origin.text,
                    style = TsukaimaType.ui,
                    color = colors.ink2,
                    softWrap = true, // never truncated: the end of the host is what the user checks
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (subject.hadHiddenChars || origin?.hadHiddenChars == true) {
                Text(
                    text = "This contains hidden characters.",
                    style = TsukaimaType.caption,
                    color = colors.warn,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        var value by remember(item.id) { mutableStateOf("") }
        var identifier by remember(item.id) { mutableStateOf("") }
        var visible by remember(item.id) { mutableStateOf(false) }
        val clear = {
            value = ""
            identifier = ""
            visible = false
        }
        val ready = when (item.kind) {
            SecretKind.VaultSaveLogin -> identifier.isNotEmpty() && value.isNotEmpty()
            else -> value.isNotEmpty()
        }
        val send = {
            val v = if (item.kind == SecretKind.VaultSaveLogin) VaultAnswers.saveLogin(identifier, value) else value
            clear()
            onSend(v)
        }
        if (!item.answered) {
            when (item.kind) {
                SecretKind.VaultCode -> TsukaimaTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = "Enter the code",
                    helper = prompt,
                    mono = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (VaultAnswers.numericCode(item.prompt)) KeyboardType.Number else KeyboardType.Text,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Send,
                    ),
                    keyboardActions = KeyboardActions(onSend = { if (ready) send() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                )
                SecretKind.VaultSaveLogin -> {
                    TsukaimaTextField(
                        value = identifier,
                        onValueChange = { identifier = it },
                        label = "Username or email",
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Next,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                    )
                    MaskedField(
                        value = value,
                        onValueChange = { value = it },
                        visible = visible,
                        onToggle = { visible = !visible },
                        label = "Password",
                        placeholder = null,
                        onSend = { if (ready) send() },
                    )
                }
                SecretKind.Sudo, SecretKind.Secret, SecretKind.VaultUnlock -> MaskedField(
                    value = value,
                    onValueChange = { value = it },
                    visible = visible,
                    onToggle = { visible = !visible },
                    label = null,
                    placeholder = if (item.kind == SecretKind.Secret) "Value" else "Password",
                    onSend = { if (ready) send() },
                )
            }
        }
        val (declineLabel, sendLabel) = when (item.kind) {
            SecretKind.VaultCode -> "Decline" to "Send"
            SecretKind.VaultSaveLogin -> "Don’t save" to "Save login"
            SecretKind.Sudo, SecretKind.Secret, SecretKind.VaultUnlock -> "Cancel" to "Send"
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
            GhostButton(declineLabel, { clear(); onCancel() }, enabled = !item.answered)
            PrimaryButton(sendLabel, send, icon = IconName.Check, enabled = !item.answered && ready)
        }
    }
}

/** A masked mono field with a show/hide toggle (44dp); IME Send calls [onSend]. */
@Composable
private fun MaskedField(
    value: String,
    onValueChange: (String) -> Unit,
    visible: Boolean,
    onToggle: () -> Unit,
    label: String?,
    placeholder: String?,
    onSend: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        verticalAlignment = if (label != null) Alignment.Bottom else Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TsukaimaTextField(
            value = value,
            onValueChange = onValueChange,
            label = label,
            placeholder = placeholder,
            mono = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Send,
            ),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            modifier = Modifier.weight(1f),
        )
        IconButton44(
            if (visible) IconName.VisibilityOff else IconName.Visibility,
            if (visible) "Hide" else "Show",
            onClick = onToggle,
        )
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
    SecretCard(
        ChatItem.SecretPrompt("srq-5", SecretKind.VaultCode, "github.com", "6-digit code from your authenticator app", answered = false),
        {}, {},
    )
    SecretCard(
        ChatItem.SecretPrompt("srq-6", SecretKind.VaultSaveLogin, "github.com", null, answered = false, origin = "https://github.com"),
        {}, {},
    )
}

@Preview(name = "SecretCard light", heightDp = 1700)
@Composable
private fun SecretLightPreview() = SecretPreview(dark = false)

@Preview(name = "SecretCard dark", heightDp = 1700)
@Composable
private fun SecretDarkPreview() = SecretPreview(dark = true)
