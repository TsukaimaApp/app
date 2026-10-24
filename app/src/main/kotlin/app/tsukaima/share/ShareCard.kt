package app.tsukaima.share

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.tsukaima.ui.chat.components.CardHeader
import app.tsukaima.ui.chat.components.FloatingCard
import app.tsukaima.ui.components.GhostButton
import app.tsukaima.ui.components.IconName
import app.tsukaima.ui.components.PrimaryButton
import app.tsukaima.ui.theme.TsukaimaTheme
import app.tsukaima.ui.theme.TsukaimaType
import app.tsukaima.ui.theme.tsukaima

/** The scrim over the sending app, the assistant sheet's: light `ink1` at 38 %, the same in dark. */
private val ShareScrim = Color(0xFF272220).copy(alpha = 0.38f)

/**
 * The share target's one card over the sending app: "Send to Hermes", the [summary] of what is shared, and where it
 * goes: **Continue last chat** ([onLastChat], only when [hasLastChat]) or **New chat** ([onNewChat], the filled button
 * when it is the only one). A tap on the scrim or Back calls [onDismiss].
 */
@Composable
fun ShareCard(
    summary: String,
    hasLastChat: Boolean,
    onLastChat: () -> Unit,
    onNewChat: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.tsukaima
    val noInteraction = remember { MutableInteractionSource() }
    BackHandler(onBack = onDismiss)
    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(ShareScrim)
                .clickable(
                    interactionSource = noInteraction,
                    indication = null,
                    onClickLabel = "Dismiss",
                    onClick = onDismiss,
                ),
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 8.dp)
                // Taps on the card never reach the scrim beneath it.
                .clickable(interactionSource = noInteraction, indication = null, onClick = {}),
        ) {
            FloatingCard {
                CardHeader(IconName.Send, "Send to Hermes")
                if (summary.isNotEmpty()) {
                    Text(
                        summary,
                        style = TsukaimaType.body,
                        color = colors.ink1,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 14.dp),
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (hasLastChat) {
                        GhostButton("New chat", onNewChat)
                        PrimaryButton("Continue last chat", onLastChat)
                    } else {
                        PrimaryButton("New chat", onNewChat)
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ShareCardPreview() = TsukaimaTheme(darkTheme = false) {
    ShareCard(summary = "Link", hasLastChat = true, onLastChat = {}, onNewChat = {}, onDismiss = {})
}
