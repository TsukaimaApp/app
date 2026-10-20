package labs.suchattai.tsukaima.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import labs.suchattai.tsukaima.ui.theme.Dimens
import labs.suchattai.tsukaima.ui.theme.TsukaimaShapes
import labs.suchattai.tsukaima.ui.theme.TsukaimaTheme
import labs.suchattai.tsukaima.ui.theme.TsukaimaType
import labs.suchattai.tsukaima.ui.theme.tsukaima

/**
 * Two-line list row (settings, cron jobs, menu items). Tappable when [onClick] is set,
 * long-pressable (480 ms) when [onLongClick] is set; with neither it is a static row.
 * Rows that are paused/disabled are dimmed by the caller via [modifier].
 */
@Composable
fun TsukaimaRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: IconName? = null,
    trailing: @Composable () -> Unit = {},
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    danger: Boolean = false,
) {
    val colors = MaterialTheme.tsukaima
    val shape = TsukaimaShapes.row
    val source = remember { MutableInteractionSource() }
    val interactive = onClick != null || onLongClick != null

    ProvideLongPressTimeout {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 52.dp)
                .clip(shape)
                .then(
                    if (interactive) {
                        Modifier
                            .pressWash(source, shape)
                            .combinedClickable(
                                interactionSource = source,
                                indication = null,
                                role = Role.Button,
                                onLongClick = onLongClick,
                                onClick = onClick ?: {},
                            )
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                TsukaimaIcon(leading, contentDescription = null, size = 22.dp, tint = if (danger) colors.danger else colors.ink2)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title,
                    style = TsukaimaType.ui,
                    color = if (danger) colors.danger else colors.ink1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = TsukaimaType.caption,
                        color = colors.ink3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            trailing()
        }
    }
}

/** Small uppercase group heading above a run of rows. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = TsukaimaType.label,
        color = MaterialTheme.tsukaima.ink3,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.gutter, vertical = 8.dp),
    )
}

@Composable
private fun RowPreview(dark: Boolean) {
    TsukaimaTheme(darkTheme = dark) {
        Column(
            Modifier
                .background(MaterialTheme.tsukaima.surfacePage)
                .padding(vertical = 16.dp),
        ) {
            SectionLabel("Jobs")
            Column(Modifier.padding(horizontal = 8.dp)) {
                TsukaimaRow(
                    title = "Nightly backup",
                    subtitle = "0 3 * * * · next 03:00",
                    leading = IconName.Schedule,
                    onClick = {},
                    onLongClick = {},
                )
                TsukaimaRow(
                    title = "Delete session",
                    leading = IconName.Delete,
                    danger = true,
                    onClick = {},
                )
                TsukaimaRow(
                    title = "Dynamic color",
                    trailing = { TsukaimaSwitch(checked = true, onCheckedChange = {}) },
                )
            }
        }
    }
}

@Preview(name = "Row light")
@Composable
private fun RowLightPreview() = RowPreview(dark = false)

@Preview(name = "Row dark")
@Composable
private fun RowDarkPreview() = RowPreview(dark = true)
