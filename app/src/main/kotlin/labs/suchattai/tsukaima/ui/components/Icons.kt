package labs.suchattai.tsukaima.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import labs.suchattai.tsukaima.ui.theme.tsukaima

/**
 * The icon vocabulary from design/HANDOFF.md (Material Symbols Rounded names), one entry per
 * name; [symbol] is the original snake_case Symbols name.
 *
 * Substitutions: the compose `material-icons-extended` set is the classic Material Icons set,
 * not Material Symbols, so glyphs are the Rounded-style classic equivalents at the default
 * weight (Symbols' weight 300 has no counterpart). Every name has an exact-name `Rounded`
 * match; no glyph had to be swapped for a different one. `arrow_back`, `chat`, `send`, `open_in_new`
 * and `receipt_long` resolve to `Icons.AutoMirrored.Rounded.*` (the non-mirrored variants are
 * deprecated and the glyphs flip in RTL, which is the intended behaviour).
 */
enum class IconName(val symbol: String) {
    Menu("menu"),
    MoreVert("more_vert"),
    ExpandMore("expand_more"),
    Add("add"),
    ArrowUpward("arrow_upward"),
    ArrowBack("arrow_back"),
    Close("close"),
    Check("check"),
    ContentCopy("content_copy"),
    Terminal("terminal"),
    Psychology("psychology"),
    Error("error"),
    GppMaybe("gpp_maybe"),
    Link("link"),
    Settings("settings"),
    MonitorHeart("monitor_heart"),
    Schedule("schedule"),
    PauseCircle("pause_circle"),
    PlayCircle("play_circle"),
    PlayArrow("play_arrow"),
    RestartAlt("restart_alt"),
    Refresh("refresh"),
    History("history"),
    ChevronRight("chevron_right"),
    Dns("dns"),
    StopCircle("stop_circle"),
    Forum("forum"),
    Chat("chat"),
    Memory("memory"),
    Folder("folder"),
    Edit("edit"),
    Archive("archive"),
    Unarchive("unarchive"),
    Delete("delete"),
    RadioButtonUnchecked("radio_button_unchecked"),
    ReceiptLong("receipt_long"),
    BarChart("bar_chart"),
    OpenInNew("open_in_new"),
    CloudOff("cloud_off"),
    Send("send"),
    Tag("tag"),
}

/** Pure lookup, safe from JVM unit tests (no Android context involved). */
fun iconFor(name: IconName): ImageVector = when (name) {
    IconName.Menu -> Icons.Rounded.Menu
    IconName.MoreVert -> Icons.Rounded.MoreVert
    IconName.ExpandMore -> Icons.Rounded.ExpandMore
    IconName.Add -> Icons.Rounded.Add
    IconName.ArrowUpward -> Icons.Rounded.ArrowUpward
    IconName.ArrowBack -> Icons.AutoMirrored.Rounded.ArrowBack
    IconName.Close -> Icons.Rounded.Close
    IconName.Check -> Icons.Rounded.Check
    IconName.ContentCopy -> Icons.Rounded.ContentCopy
    IconName.Terminal -> Icons.Rounded.Terminal
    IconName.Psychology -> Icons.Rounded.Psychology
    IconName.Error -> Icons.Rounded.Error
    IconName.GppMaybe -> Icons.Rounded.GppMaybe
    IconName.Link -> Icons.Rounded.Link
    IconName.Settings -> Icons.Rounded.Settings
    IconName.MonitorHeart -> Icons.Rounded.MonitorHeart
    IconName.Schedule -> Icons.Rounded.Schedule
    IconName.PauseCircle -> Icons.Rounded.PauseCircle
    IconName.PlayCircle -> Icons.Rounded.PlayCircle
    IconName.PlayArrow -> Icons.Rounded.PlayArrow
    IconName.RestartAlt -> Icons.Rounded.RestartAlt
    IconName.Refresh -> Icons.Rounded.Refresh
    IconName.History -> Icons.Rounded.History
    IconName.ChevronRight -> Icons.Rounded.ChevronRight
    IconName.Dns -> Icons.Rounded.Dns
    IconName.StopCircle -> Icons.Rounded.StopCircle
    IconName.Forum -> Icons.Rounded.Forum
    IconName.Chat -> Icons.AutoMirrored.Rounded.Chat
    IconName.Memory -> Icons.Rounded.Memory
    IconName.Folder -> Icons.Rounded.Folder
    IconName.Edit -> Icons.Rounded.Edit
    IconName.Archive -> Icons.Rounded.Archive
    IconName.Unarchive -> Icons.Rounded.Unarchive
    IconName.Delete -> Icons.Rounded.Delete
    IconName.RadioButtonUnchecked -> Icons.Rounded.RadioButtonUnchecked
    IconName.ReceiptLong -> Icons.AutoMirrored.Rounded.ReceiptLong
    IconName.BarChart -> Icons.Rounded.BarChart
    IconName.OpenInNew -> Icons.AutoMirrored.Rounded.OpenInNew
    IconName.CloudOff -> Icons.Rounded.CloudOff
    IconName.Send -> Icons.AutoMirrored.Rounded.Send
    IconName.Tag -> Icons.Rounded.Tag
}

/**
 * Decorative icon (`aria-hidden` in the design); pass [contentDescription] only when the icon
 * is the sole carrier of meaning. Default tint is ink2, matching the `--ink-2` context.
 */
@Composable
fun TsukaimaIcon(
    name: IconName,
    size: Dp = 24.dp,
    tint: Color = MaterialTheme.tsukaima.ink2,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    Icon(
        imageVector = iconFor(name),
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        tint = tint,
    )
}
