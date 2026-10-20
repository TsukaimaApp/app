package labs.suchattai.tsukaima.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import labs.suchattai.tsukaima.ui.components.Chip
import labs.suchattai.tsukaima.ui.components.IconButton44
import labs.suchattai.tsukaima.ui.components.IconName
import labs.suchattai.tsukaima.ui.theme.tsukaima

/** Near-invisible top bar on the page colour: drawer menu, borderless model chip, overflow. */
@Composable
fun ChatTopBar(
    modelLabel: String,
    onMenu: () -> Unit,
    showMenu: Boolean,
    onModel: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.tsukaima.surfacePage)
            .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (showMenu) IconButton44(IconName.Menu, "Conversations", onMenu)
        Chip(modelLabel, onClick = onModel, trailing = IconName.ExpandMore, borderless = true)
        Spacer(Modifier.weight(1f))
        IconButton44(IconName.MoreVert, "More", onMore)
    }
}

@Composable
private fun TopBarPreview(dark: Boolean) = PreviewPage(dark) {
    ChatTopBar("Hermes 4 · 405B", {}, showMenu = true, onModel = {}, onMore = {})
    ChatTopBar("Hermes 4", {}, showMenu = false, onModel = {}, onMore = {})
}

@Preview(name = "ChatTopBar light")
@Composable
private fun TopBarLightPreview() = TopBarPreview(dark = false)

@Preview(name = "ChatTopBar dark")
@Composable
private fun TopBarDarkPreview() = TopBarPreview(dark = true)
