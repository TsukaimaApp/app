package app.tsukaima.ui.chat.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.tsukaima.data.api.ImageOrigin
import app.tsukaima.domain.chat.ChatItem
import app.tsukaima.domain.chat.ToolRisk
import app.tsukaima.domain.chat.ToolStatus
import app.tsukaima.ui.chat.ImageResult
import app.tsukaima.ui.chat.ToolImageSource
import app.tsukaima.ui.components.IconName
import app.tsukaima.ui.components.SkeletonBlock
import app.tsukaima.ui.components.TsukaimaIcon
import app.tsukaima.ui.components.pressWash
import app.tsukaima.ui.theme.Dimens
import app.tsukaima.ui.theme.Motion
import app.tsukaima.ui.theme.TsukaimaShapes
import app.tsukaima.ui.theme.TsukaimaType
import app.tsukaima.ui.theme.tsukaima
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Slim expandable row for a tool call: quiet by default, output on tap. [margin] is its outer spacing (none in the
 * assist sheet). A `vision_analyze` row shows the image it looked at under the header ([ToolImage]) when [media] is
 * given.
 */
@Composable
fun ToolRow(
    item: ChatItem.ToolCall,
    modifier: Modifier = Modifier,
    margin: PaddingValues = PaddingValues(horizontal = Dimens.gutter, vertical = 6.dp),
    media: MediaHost? = null,
) {
    val colors = MaterialTheme.tsukaima
    var open by rememberSaveable(item.id) { mutableStateOf(false) }
    val source = remember { MutableInteractionSource() }
    val rotation by animateFloatAsState(if (open) 180f else 0f, tween(Motion.base, easing = Motion.easing), label = "toolChevron")
    val thinking = item.name.contains("think", ignoreCase = true) || item.name.contains("reason", ignoreCase = true)
    val running = item.status == ToolStatus.Running
    val shape = TsukaimaShapes.row
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(margin)
            .clip(shape)
            .background(colors.surfaceRaised, shape)
            .border(1.dp, colors.hairline, shape)
            .animateContentSize(tween(Motion.base, easing = Motion.easing)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = Dimens.hit)
                .pressWash(source)
                .clickable(interactionSource = source, indication = null, role = Role.Button) { open = !open }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TsukaimaIcon(
                if (thinking) IconName.Psychology else IconName.Terminal,
                contentDescription = null,
                size = 18.dp,
                tint = if (running) colors.accent else colors.ink3,
            )
            Text(
                text = buildAnnotatedString {
                    append(item.name)
                    if (!item.context.isNullOrBlank()) {
                        withStyle(SpanStyle(color = colors.ink3)) { append(" · ${item.context}") }
                    }
                },
                style = TsukaimaType.ui,
                color = colors.ink2,
                // One line while collapsed; the tap that opens the row also unfolds the whole command.
                maxLines = if (open) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            when (item.status) {
                ToolStatus.Running -> Unit
                ToolStatus.Interrupted -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusDot(StatusVisual(colors.warn, pulsing = false))
                    Text("Interrupted", style = TsukaimaType.caption, color = colors.warn, maxLines = 1)
                }
                ToolStatus.Failed -> TsukaimaIcon(IconName.Error, size = 16.dp, tint = colors.danger, contentDescription = "Failed")
                ToolStatus.Done -> TsukaimaIcon(IconName.Check, size = 16.dp, tint = colors.ink3, contentDescription = "Done")
            }
            TsukaimaIcon(IconName.ExpandMore, contentDescription = null, size = 18.dp, tint = colors.ink2, modifier = Modifier.rotate(rotation))
        }
        item.risk?.let { RiskNote(it) }
        if (media != null) item.imageUrl?.let { ToolImage(item, it, media) }
        if (open) {
            // Without a server-written summary the arguments are the only account of what the tool was asked to do.
            if (item.context.isNullOrBlank()) argsText(item.args)?.let { args ->
                Text(
                    text = args,
                    style = TsukaimaType.caption.copy(fontFamily = TsukaimaType.mono.fontFamily),
                    color = colors.ink3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                )
            }
            Text(
                text = item.output?.takeIf { it.isNotBlank() } ?: "No output",
                style = if (thinking) TsukaimaType.caption else TsukaimaType.caption.copy(fontFamily = TsukaimaType.mono.fontFamily),
                color = colors.ink2,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            )
        }
        // Always visible, like the risk note: a stopped tool's effect is something to check before retrying.
        item.orphanNote?.let { WarnLine(it) }
    }
}

/** Where a [ToolImage] is: waiting for a tap (a foreign http(s) origin), loading, shown, or unavailable. */
private sealed interface ThumbLoad {
    data object Blocked : ThumbLoad
    data object Loading : ThumbLoad
    data class Loaded(val bitmap: ImageBitmap) : ThumbLoad
    data object Failed : ThumbLoad
    data object Retry : ThumbLoad
}

/**
 * The image a `vision_analyze` call looked at ([raw], its [ChatItem.ToolCall.imageUrl]), loaded through
 * [MediaHost.toolImage]: a [THUMB] thumbnail, a tap shows it full-width (and back). A foreign http(s) image waits for
 * a tap like a reply's ([MediaHost.imageAllowed]); a scheme it cannot fetch shows nothing; a final failure shows only
 * "image unavailable", a transient one (connection dropped) offers a tap to retry.
 */
@Composable
private fun ToolImage(item: ChatItem.ToolCall, raw: String, host: MediaHost) {
    val source = remember(raw) { ToolImageSource.of(raw) } ?: return
    val web = (source as? ToolImageSource.Web)?.url
    val colors = MaterialTheme.tsukaima
    // Read in composition: a tap on the same URL anywhere (another row, a reply's image) loads it here too.
    val allowed = web == null || host.imageAllowed(web)
    var attempt by remember(raw) { mutableIntStateOf(0) }
    var full by rememberSaveable(item.id) { mutableStateOf(false) }
    val tool by rememberUpdatedState(item)
    val load by produceState<ThumbLoad>(if (allowed) ThumbLoad.Loading else ThumbLoad.Blocked, raw, allowed, attempt) {
        if (!allowed) {
            value = ThumbLoad.Blocked
            return@produceState
        }
        value = ThumbLoad.Loading
        value = when (val result = host.toolImage(tool)) {
            is ImageResult.Loaded -> ThumbLoad.Loaded(result.bitmap)
            ImageResult.Unavailable -> ThumbLoad.Failed
            ImageResult.Retry -> ThumbLoad.Retry
        }
    }
    val shape = TsukaimaShapes.code
    val pad = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
    when (val l = load) {
        ThumbLoad.Blocked -> TapCaption("From ${web?.let(ImageOrigin::host) ?: "another site"} — tap to load", "Load image") {
            web?.let(host.allowImage)
        }
        ThumbLoad.Retry -> TapCaption("image didn’t load — tap to retry", "Retry image") { attempt++ }
        ThumbLoad.Loading -> SkeletonBlock(pad.size(THUMB), radius = 10.dp)
        is ThumbLoad.Loaded -> Image(
            bitmap = l.bitmap,
            contentDescription = "Image the tool looked at",
            contentScale = if (full) ContentScale.Fit else ContentScale.Crop,
            modifier = pad
                .then(if (full) Modifier.fillMaxWidth() else Modifier.size(THUMB))
                .clip(shape)
                .border(1.dp, colors.hairline, shape)
                .clickable(role = Role.Button, onClickLabel = if (full) "Show thumbnail" else "Show full image") { full = !full },
        )
        ThumbLoad.Failed -> Text("image unavailable", style = TsukaimaType.caption, color = colors.ink3, modifier = pad)
    }
}

/** A one-line ink3 caption with an image icon that is a 44dp tap target ([ToolImage]'s tap to load / retry). */
@Composable
private fun TapCaption(text: String, clickLabel: String, onClick: () -> Unit) {
    val colors = MaterialTheme.tsukaima
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Dimens.hit)
            .clickable(role = Role.Button, onClickLabel = clickLabel, onClick = onClick)
            .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TsukaimaIcon(IconName.Image, contentDescription = null, size = 18.dp, tint = colors.ink3)
        Text(text, style = TsukaimaType.caption, color = colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private val THUMB = 96.dp

/** `tool.output_risk` under the header: a small amber line, always visible (not only when expanded). */
@Composable
private fun RiskNote(risk: ToolRisk) = WarnLine(riskText(risk))

/** A small amber warning line (icon + caption) inside a tool row: the risk note, an orphan-recovery note. */
@Composable
private fun WarnLine(text: String) {
    val colors = MaterialTheme.tsukaima
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TsukaimaIcon(IconName.Warning, contentDescription = null, size = 14.dp, tint = colors.warn)
        Text(text, style = TsukaimaType.caption, color = colors.warn)
    }
}

/**
 * The tool's arguments as `key: value` lines (a string value verbatim, anything else as JSON), shown in an open
 * row that has no [ChatItem.ToolCall.context]. Null when there are none.
 */
internal fun argsText(args: JsonObject?): String? =
    args?.takeIf { it.isNotEmpty() }?.entries?.joinToString("\n") { (key, value) ->
        "$key: ${(value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: value.toString()}"
    }

/** "Output flagged: high risk · prompt injection · redacted". */
internal fun riskText(risk: ToolRisk): String =
    (listOf("Output flagged: ${risk.level} risk") + risk.findings.map { it.replace('_', ' ') } + listOfNotNull("redacted".takeIf { risk.redacted }))
        .joinToString(" · ")

private fun sample(status: ToolStatus, output: String? = "ok  agent.log: 50 lines") = ChatItem.ToolCall(
    id = "t-$status", name = "shell", context = "tail -n 50 /var/log/hermes/agent.log --follow --verbose --lines=50",
    args = null, status = status, output = output, exitCode = null, durationS = null,
)

@Composable
private fun ToolRowPreview(dark: Boolean) = PreviewPage(dark) {
    ToolRow(sample(ToolStatus.Done))
    ToolRow(sample(ToolStatus.Running))
    ToolRow(sample(ToolStatus.Failed, null))
    ToolRow(sample(ToolStatus.Interrupted, "partial").copy(id = "t-orphan", orphanNote = "May have partially run — check before retrying"))
    ToolRow(sample(ToolStatus.Done).copy(id = "t-risk", risk = ToolRisk("high", listOf("prompt_injection"), redacted = true)))
}

@Preview(name = "ToolRow light")
@Composable
private fun ToolRowLightPreview() = ToolRowPreview(dark = false)

@Preview(name = "ToolRow dark")
@Composable
private fun ToolRowDarkPreview() = ToolRowPreview(dark = true)
