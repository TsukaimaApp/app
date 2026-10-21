package app.tsukaima.ui.drawer

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.tsukaima.ui.components.IconName
import app.tsukaima.ui.components.ProvideLongPressTimeout
import app.tsukaima.ui.components.TsukaimaIcon
import app.tsukaima.ui.components.pressWash
import app.tsukaima.ui.theme.TsukaimaShapes
import app.tsukaima.ui.theme.TsukaimaTheme
import app.tsukaima.ui.theme.TsukaimaType
import app.tsukaima.ui.theme.tsukaima

/**
 * Drawer row (`ConversationItem.jsx`): 44dp min, 10×12dp padding, 12dp radius, ui 14sp title with an optional
 * leading 20dp [icon] and trailing caption [meta]. [selected] fills it with `accentSelected` (ink text) and
 * suppresses the press wash. [onLongClick] fires after 480 ms; a long press does not also click.
 */
@Composable
fun ConversationItem(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    meta: String? = null,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    icon: IconName? = null,
) {
    val colors = MaterialTheme.tsukaima
    val shape = TsukaimaShapes.row
    val source = remember { MutableInteractionSource() }
    ProvideLongPressTimeout {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 44.dp)
                .clip(shape)
                .then(if (selected) Modifier.background(colors.accentSelected) else Modifier.pressWash(source, shape))
                .semantics { this.selected = selected }
                .combinedClickable(
                    interactionSource = source,
                    indication = null,
                    role = Role.Button,
                    onLongClick = onLongClick,
                    onClick = onClick,
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) TsukaimaIcon(icon, contentDescription = null, size = 20.dp, tint = colors.ink2)
            Text(
                title,
                style = TsukaimaType.ui,
                color = colors.ink1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!meta.isNullOrEmpty()) {
                Text(meta, style = TsukaimaType.caption, color = colors.ink3, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ConversationItemPreview(dark: Boolean) {
    TsukaimaTheme(darkTheme = dark) {
        Column(
            Modifier
                .width(296.dp)
                .background(MaterialTheme.tsukaima.surfaceRaised)
                .padding(8.dp),
        ) {
            ConversationItem("New chat", icon = IconName.Add, onClick = {})
            ConversationItem("Fix the cron job", meta = "2h", selected = true, onClick = {}, onLongClick = {})
            ConversationItem("Summarize server logs from last night's run", meta = "1d", onClick = {}, onLongClick = {})
            ConversationItem("Archived", meta = "3", icon = IconName.Archive, onClick = {})
        }
    }
}

@Preview(name = "ConversationItem light")
@Composable
private fun ConversationItemLightPreview() = ConversationItemPreview(dark = false)

@Preview(name = "ConversationItem dark")
@Composable
private fun ConversationItemDarkPreview() = ConversationItemPreview(dark = true)
