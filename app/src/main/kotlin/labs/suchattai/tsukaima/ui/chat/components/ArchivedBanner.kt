package labs.suchattai.tsukaima.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import labs.suchattai.tsukaima.ui.components.ButtonSize
import labs.suchattai.tsukaima.ui.components.IconName
import labs.suchattai.tsukaima.ui.components.SecondaryButton
import labs.suchattai.tsukaima.ui.components.TsukaimaIcon
import labs.suchattai.tsukaima.ui.theme.TsukaimaShapes
import labs.suchattai.tsukaima.ui.theme.TsukaimaType
import labs.suchattai.tsukaima.ui.theme.tsukaima

/** Above the (disabled) composer of an archived chat (HANDOFF "archived-session read-only banner"). */
@Composable
fun ArchivedBanner(onUnarchive: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.tsukaima
    val shape = TsukaimaShapes.row
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp)
            .background(colors.surfaceRaised, shape)
            .border(1.dp, colors.hairline, shape)
            .padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TsukaimaIcon(IconName.Archive, contentDescription = null, size = 18.dp, tint = colors.ink3)
        Text("This chat is archived", style = TsukaimaType.ui, color = colors.ink2, modifier = Modifier.weight(1f))
        SecondaryButton("Unarchive", onUnarchive, size = ButtonSize.Sm, icon = IconName.Unarchive)
    }
}

@Composable
private fun ArchivedBannerPreview(dark: Boolean) = PreviewPage(dark) { ArchivedBanner({}) }

@Preview(name = "ArchivedBanner light")
@Composable
private fun ArchivedBannerLightPreview() = ArchivedBannerPreview(dark = false)

@Preview(name = "ArchivedBanner dark")
@Composable
private fun ArchivedBannerDarkPreview() = ArchivedBannerPreview(dark = true)
