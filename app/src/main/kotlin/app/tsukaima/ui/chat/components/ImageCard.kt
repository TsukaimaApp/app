package app.tsukaima.ui.chat.components

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import app.tsukaima.data.api.ImageOrigin
import app.tsukaima.ui.components.IconName
import app.tsukaima.ui.components.SkeletonBlock
import app.tsukaima.ui.components.TsukaimaIcon
import app.tsukaima.ui.components.pressWash
import app.tsukaima.ui.theme.Dimens
import app.tsukaima.ui.theme.TsukaimaShapes
import app.tsukaima.ui.theme.TsukaimaType
import app.tsukaima.ui.theme.tsukaima
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Where an [ImageCard] is: waiting for a tap (a foreign origin), still loading, shown, or failed (then the alt text
 * stands in).
 */
private sealed interface ImageLoad {
    data object Blocked : ImageLoad
    data object Loading : ImageLoad
    data class Loaded(val bitmap: ImageBitmap) : ImageLoad
    data object Failed : ImageLoad
}

/**
 * An image a reply shows with a whole-line `![alt](url)`: loaded through [MediaHost.previewImage] and fitted to the
 * width (at most 280dp tall) on the raised surface with a hairline. While it loads a skeleton pulses; when it fails,
 * the [alt] text (or "Image") stands in beside a broken-image icon. A tap opens [url] in the browser.
 *
 * An image on another origin than the server's does not load by itself ([MediaHost.imageAllowed]): a
 * prompt-injected reply could leak data through its URL. It shows the alt and "From <host> — tap to load"; the tap
 * ([MediaHost.allowImage]) loads it, and from then on it behaves like any other.
 */
@Composable
fun ImageCard(alt: String, url: String, host: MediaHost, modifier: Modifier = Modifier) {
    var allowed by remember(url) { mutableStateOf(host.imageAllowed(url)) }
    val load by produceState<ImageLoad>(if (allowed) ImageLoad.Loading else ImageLoad.Blocked, url, allowed) {
        if (!allowed) {
            value = ImageLoad.Blocked
            return@produceState
        }
        value = ImageLoad.Loading
        value = host.previewImage(url)?.let { ImageLoad.Loaded(it) } ?: ImageLoad.Failed
    }
    ImageCardContent(alt, url, load, modifier) {
        host.allowImage(url)
        allowed = true
    }
}

@Composable
private fun ImageCardContent(
    alt: String,
    url: String,
    load: ImageLoad,
    modifier: Modifier = Modifier,
    onLoad: () -> Unit = {},
) {
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
            .clickable(
                interactionSource = source,
                indication = null,
                role = Role.Button,
                onClickLabel = if (load == ImageLoad.Blocked) "Load image" else "Open in browser",
            ) {
                if (load == ImageLoad.Blocked) {
                    onLoad()
                    return@clickable
                }
                try {
                    uriHandler.openUri(url)
                } catch (e: Exception) {
                    // No browser (ActivityNotFoundException) or a URI the platform rejects: nothing to open. Only the
                    // host and the failure's type are logged: the full URL (and a message quoting it) rides into
                    // problem reports.
                    Log.w("Tsukaima", "image: cannot open a link on ${url.toHttpUrlOrNull()?.host} (${e.javaClass.simpleName})")
                }
            }
            .semantics(mergeDescendants = true) { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        when (load) {
            ImageLoad.Blocked -> Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TsukaimaIcon(IconName.Image, contentDescription = null, size = 20.dp, tint = colors.ink2)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = label,
                        style = TsukaimaType.ui,
                        color = colors.ink1,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "From ${ImageOrigin.host(url) ?: "another site"} — tap to load",
                        style = TsukaimaType.caption,
                        color = colors.ink3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
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
        ImageCardContent("Satellite view of the site", "https://tiles.example.org/z/12/x.png", ImageLoad.Blocked)
    }
}

@Preview(name = "ImageCard light")
@Composable
private fun ImageCardLightPreview() = ImageCardPreview(dark = false)

@Preview(name = "ImageCard dark")
@Composable
private fun ImageCardDarkPreview() = ImageCardPreview(dark = true)
