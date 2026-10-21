package app.tsukaima.ui.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.tsukaima.ui.components.GhostButton
import app.tsukaima.ui.components.IconName
import app.tsukaima.ui.components.PrimaryButton
import app.tsukaima.ui.components.Tone
import app.tsukaima.ui.theme.TsukaimaType
import app.tsukaima.ui.theme.tsukaima

/** Someone messaged the bot and needs approval; the code is large mono so it can be matched by eye. */
@Composable
fun PairingCard(
    platform: String,
    who: String,
    code: String,
    onApprove: () -> Unit,
    modifier: Modifier = Modifier,
    onRevoke: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.tsukaima
    FloatingCard(modifier) {
        CardHeader(IconName.Link, "Pairing request from $platform")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 14.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = who,
                style = TsukaimaType.ui,
                color = colors.ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = code,
                style = TsukaimaType.mono.copy(fontSize = 20.sp, letterSpacing = 0.18.em),
                color = colors.ink1,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
        ) {
            if (onRevoke != null) GhostButton("Revoke", onRevoke, tone = Tone.Danger)
            PrimaryButton("Approve", onApprove, icon = IconName.Check)
        }
    }
}

@Composable
private fun PairingPreview(dark: Boolean) = PreviewPage(dark) {
    PairingCard("Telegram", "@mario", "4F2-91K", onApprove = {}, onRevoke = {})
}

@Preview(name = "PairingCard light")
@Composable
private fun PairingLightPreview() = PairingPreview(dark = false)

@Preview(name = "PairingCard dark")
@Composable
private fun PairingDarkPreview() = PairingPreview(dark = true)
