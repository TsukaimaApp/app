package labs.suchattai.tsukaima.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import labs.suchattai.tsukaima.ui.chat.components.PreviewPage
import labs.suchattai.tsukaima.ui.components.Chip
import labs.suchattai.tsukaima.ui.components.IconName
import labs.suchattai.tsukaima.ui.theme.Dimens
import labs.suchattai.tsukaima.ui.theme.TsukaimaType
import labs.suchattai.tsukaima.ui.theme.tsukaima

/**
 * A fresh chat (HANDOFF §3, chat-screen.jsx): "New chat" in serif italic 16sp, the "Working directory"
 * caption and a folder chip that opens the workspace picker. Sits 18 % of the height down, like the jsx's 18vh.
 */
@Composable
fun EmptyChat(workspace: String?, onPickWorkspace: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.tsukaima
    BoxWithConstraints(modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = Dimens.gutter, end = Dimens.gutter, top = maxHeight * 0.18f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "New chat",
                style = TsukaimaType.statusLine.copy(fontSize = 16.sp),
                color = colors.ink2,
                textAlign = TextAlign.Center,
            )
            Text("Working directory", style = TsukaimaType.caption, color = colors.ink3)
            Chip(
                label = workspace ?: "Default",
                onClick = onPickWorkspace,
                leading = IconName.Folder,
                trailing = IconName.ExpandMore,
            )
        }
    }
}

@Composable
private fun EmptyChatPreview(dark: Boolean) = PreviewPage(dark) {
    EmptyChat("~/projects/hermes", onPickWorkspace = {}, modifier = Modifier.padding(bottom = 24.dp))
}

@Preview(name = "EmptyChat light", heightDp = 320)
@Composable
private fun EmptyChatLightPreview() = EmptyChatPreview(dark = false)

@Preview(name = "EmptyChat dark", heightDp = 320)
@Composable
private fun EmptyChatDarkPreview() = EmptyChatPreview(dark = true)
