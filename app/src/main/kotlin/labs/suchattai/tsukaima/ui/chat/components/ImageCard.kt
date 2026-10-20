package labs.suchattai.tsukaima.ui.chat.components

import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import labs.suchattai.tsukaima.ui.components.IconName
import labs.suchattai.tsukaima.ui.components.SkeletonBlock
import labs.suchattai.tsukaima.ui.components.TsukaimaIcon
import labs.suchattai.tsukaima.ui.components.pressWash
import labs.suchattai.tsukaima.ui.theme.Dimens
import labs.suchattai.tsukaima.ui.theme.TsukaimaShapes
import labs.suchattai.tsukaima.ui.theme.TsukaimaType
import labs.suchattai.tsukaima.ui.theme.tsukaima

/** Where an [ImageCard] is: still loading, shown, or failed (then the alt text stands in). */
private sealed interface ImageLoad {
    data object Loading : ImageLoad
    data class Loaded(val bitmap: ImageBitmap) : ImageLoad
    data object Failed : ImageLoad
}

/**
 * An image a reply shows with a whole-line `![alt](url)`: loaded through [MediaHost.previewImage] and fitted to the
 * width (at most 280dp tall) on the raised surface with a hairline. While it loads a skeleton pulses; when it fails,
 * the [alt] text (or "Image") stands in beside a broken-image icon. A tap opens [url] in the browser.
 */
@Composable
fun ImageCard(alt: String, url: String, host: MediaHost, modifier: Modifier = Modifier) {
    val load by produceState<ImageLoad>(ImageLoad.Loading, url) {
        value = host.previewImage(url)?.let { ImageLoad.Loaded(it) } ?: ImageLoad.Failed
    }
    ImageCardContent(alt, url, load, modifier)
}

@Composable
private fun ImageCardContent(alt: String, url: String, load: ImageLoad, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.tsukaima
    val uriHandler = LocalUriHandler.current
    val source = remember { MutableInteractionSource() }
    val shape = TsukaimaShapes.row
    val label = alt.ifBlank { "Image" }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Dimens.hit)
            .clip(shape)
            .background(colors.surfaceRaised, shape)
            .border(1.dp, colors.hairline, shape)
            .pressWash(source, shape)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClickLabel = "Open in browser") {
                try {
                    uriHandler.openUri(url)
                } catch (e: Exception) {
                    // No browser (ActivityNotFoundException) or a URI the platform rejects: nothing to open.
                    Log.w("Tsukaima", "image: cannot open $url", e)
                }
            }
            .semantics(mergeDescendants = true) { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        when (load) {
            ImageLoad.Loading -> SkeletonBlock(Modifier.fillMaxWidth().height(LOADING_HEIGHT), radius = 0.dp)
            is ImageLoad.Loaded -> Image(
                bitmap = load.bitmap,
                contentDescription = label,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp),
            )
            ImageLoad.Failed -> Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TsukaimaIcon(IconName.BrokenImage, contentDescription = null, size = 20.dp, tint = colors.ink3)
                Text(
                    text = label,
                    style = TsukaimaType.caption,
                    color = colors.ink3,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private val LOADING_HEIGHT = 160.dp

/** A 2×2 checker for the previews (no network in the preview pane). */
private fun sampleBitmap(): ImageBitmap = createBitmap(2, 2).apply {
    val a = Color(0xFF6B8FB8).toArgb()
    val b = Color(0xFFE8D9B0).toArgb()
    this[0, 0] = a
    this[1, 1] = a
    this[1, 0] = b
    this[0, 1] = b
}.asImageBitmap()

private const val SampleUrl = "https://example.com/cpu-load.png"

@Composable
private fun ImageCardPreview(dark: Boolean) = PreviewPage(dark) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ImageCardContent("CPU load over the last day", SampleUrl, ImageLoad.Loaded(sampleBitmap()))
        ImageCardContent("Memory", SampleUrl, ImageLoad.Loading)
        ImageCardContent("Disk usage by mount point", SampleUrl, ImageLoad.Failed)
        ImageCardContent("", SampleUrl, ImageLoad.Failed)
    }
}

@Preview(name = "ImageCard light")
@Composable
private fun ImageCardLightPreview() = ImageCardPreview(dark = false)

@Preview(name = "ImageCard dark")
@Composable
private fun ImageCardDarkPreview() = ImageCardPreview(dark = true)
