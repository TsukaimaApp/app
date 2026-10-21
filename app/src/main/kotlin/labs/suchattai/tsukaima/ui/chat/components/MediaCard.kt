package labs.suchattai.tsukaima.ui.chat.components

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import labs.suchattai.tsukaima.domain.chat.ChatItem
import labs.suchattai.tsukaima.ui.chat.ImageResult
import labs.suchattai.tsukaima.ui.chat.Media
import labs.suchattai.tsukaima.ui.chat.MediaKind
import labs.suchattai.tsukaima.ui.chat.MediaState
import labs.suchattai.tsukaima.ui.chat.MediaStatus
import labs.suchattai.tsukaima.ui.components.IconName
import labs.suchattai.tsukaima.ui.components.TsukaimaIcon
import labs.suchattai.tsukaima.ui.components.pressWash
import labs.suchattai.tsukaima.ui.theme.Dimens
import labs.suchattai.tsukaima.ui.theme.TsukaimaShapes
import labs.suchattai.tsukaima.ui.theme.TsukaimaType
import labs.suchattai.tsukaima.ui.theme.tsukaima

/**
 * What `MEDIA:` cards need from the chat: download state per path, the tap, and the image preview loader; plus,
 * for a reply's `![alt](url)` images ([ImageCard]), [previewImage] (null when one fails), [imageAllowed] (whether
 * one loads by itself: server origin or already tapped) and [allowImage] (the tap on a foreign one); and
 * [toolImage], the image a `vision_analyze` tool row looked at (or why there is none).
 */
class MediaHost(
    val states: Map<String, MediaState> = emptyMap(),
    val onOpen: (String) -> Unit = {},
    val preview: suspend (String) -> ImageBitmap? = { null },
    val previewImage: suspend (String) -> ImageBitmap? = { null },
    val imageAllowed: (String) -> Boolean = { false },
    val allowImage: (String) -> Unit = {},
    val toolImage: suspend (ChatItem.ToolCall) -> ImageResult = { ImageResult.Unavailable },
)

private fun iconFor(kind: MediaKind) = when (kind) {
    MediaKind.Image -> IconName.Image
    MediaKind.Video -> IconName.VideoFile
    MediaKind.Audio -> IconName.AudioFile
    MediaKind.Document -> IconName.Description
    MediaKind.Archive -> IconName.FolderZip
    MediaKind.Other -> IconName.InsertDriveFile
}

/**
 * A file the agent delivered (`MEDIA:` line): kind icon, bold name, shortened folder, and a download action.
 * The whole card downloads on tap; while downloading the tap is ignored, a failure shows its reason and
 * retries on tap. Images get an inline preview above the row when one loads.
 */
@Composable
fun MediaCard(path: String, host: MediaHost, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.tsukaima
    val state = host.states[path]
    val name = Media.basename(path)
    val failed = state?.status == MediaStatus.Failed
    val downloading = state?.status == MediaStatus.Downloading
    val preview by produceState<ImageBitmap?>(null, path) {
        if (Media.previewable(path)) value = host.preview(path)
    }
    val source = remember { MutableInteractionSource() }
    val shape = TsukaimaShapes.row
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceRaised, shape)
            .border(1.dp, if (failed) colors.danger else colors.hairline, shape)
            .pressWash(source, shape)
            .clickable(
                interactionSource = source,
                indication = null,
                enabled = !downloading,
                role = Role.Button,
                onClickLabel = if (failed) "Retry download" else "Download $name",
            ) { host.onOpen(path) }
            .semantics(mergeDescendants = true) {
                stateDescription = when (state?.status) {
                    MediaStatus.Downloading -> "Downloading"
                    MediaStatus.Saved -> "Saved to Downloads"
                    MediaStatus.Failed -> "Download failed, tap to retry"
                    null -> "File"
                }
            },
    ) {
        preview?.let { bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp)
                    .background(colors.surfaceSunken),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = Dimens.hit)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .background(colors.surfaceSunken, TsukaimaShapes.code),
                contentAlignment = Alignment.Center,
            ) {
                TsukaimaIcon(iconFor(Media.kind(path)), contentDescription = null, size = 20.dp, tint = if (failed) colors.danger else colors.ink2)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = name,
                    style = TsukaimaType.ui.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.ink1,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
                Text(
                    text = if (failed) state.error ?: "Download failed" else Media.parentLabel(path),
                    style = TsukaimaType.caption,
                    color = if (failed) colors.danger else colors.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                when (state?.status) {
                    MediaStatus.Downloading -> CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = colors.accent,
                        trackColor = colors.hairline,
                        strokeWidth = 2.dp,
                    )
                    MediaStatus.Saved -> TsukaimaIcon(IconName.Check, size = 20.dp, tint = colors.ok, contentDescription = "Saved")
                    MediaStatus.Failed -> TsukaimaIcon(IconName.Refresh, size = 20.dp, tint = colors.danger, contentDescription = "Retry")
                    null -> TsukaimaIcon(IconName.Download, size = 20.dp, tint = colors.ink2, contentDescription = "Download")
                }
            }
        }
    }
}

private const val SamplePath = "/home/hermes/workspace/output/session-20261020_075645_44ae73/20261020_075645_44ae73.debug.txt"

@Composable
private fun MediaCardPreview(dark: Boolean) = PreviewPage(dark) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MediaCard(SamplePath, MediaHost())
        MediaCard("~/out/report.pdf", MediaHost(mapOf("~/out/report.pdf" to MediaState(MediaStatus.Downloading))))
        MediaCard("/tmp/song.mp3", MediaHost(mapOf("/tmp/song.mp3" to MediaState(MediaStatus.Saved))))
        MediaCard(
            "/tmp/build/archive.tar.gz",
            MediaHost(mapOf("/tmp/build/archive.tar.gz" to MediaState(MediaStatus.Failed, "File no longer exists on the server"))),
        )
    }
}

@Preview(name = "MediaCard light")
@Composable
private fun MediaCardLightPreview() = MediaCardPreview(dark = false)

@Preview(name = "MediaCard dark")
@Composable
private fun MediaCardDarkPreview() = MediaCardPreview(dark = true)
