package labs.suchattai.tsukaima.ui.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import labs.suchattai.tsukaima.ui.components.SkeletonBlock
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import labs.suchattai.tsukaima.ui.theme.Dimens
import labs.suchattai.tsukaima.ui.theme.TsukaimaType
import labs.suchattai.tsukaima.ui.theme.tsukaima

/**
 * Loading placeholder for the transcript: a user bubble, assistant text lines and a tool row; [caption] (a
 * `session.resume_progress` line) above them when the server reports one.
 */
@Composable
fun ChatSkeleton(modifier: Modifier = Modifier, caption: String? = null) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.gutter, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (caption != null) Text(caption, style = TsukaimaType.caption, color = MaterialTheme.tsukaima.ink3)
        SkeletonBlock(Modifier.fillMaxWidth(0.56f).height(40.dp).align(Alignment.End), radius = 16.dp)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SkeletonBlock(Modifier.fillMaxWidth(0.92f).height(14.dp))
            SkeletonBlock(Modifier.fillMaxWidth(0.78f).height(14.dp))
            SkeletonBlock(Modifier.fillMaxWidth(0.40f).height(14.dp))
        }
        SkeletonBlock(Modifier.fillMaxWidth().height(Dimens.hit), radius = 12.dp)
    }
}

@Composable
private fun SkeletonPreview(dark: Boolean) = PreviewPage(dark) { ChatSkeleton(caption = "Loading history…") }

@Preview(name = "ChatSkeleton light")
@Composable
private fun SkeletonLightPreview() = SkeletonPreview(dark = false)

@Preview(name = "ChatSkeleton dark")
@Composable
private fun SkeletonDarkPreview() = SkeletonPreview(dark = true)
