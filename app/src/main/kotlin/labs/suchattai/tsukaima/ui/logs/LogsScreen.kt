package labs.suchattai.tsukaima.ui.logs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import labs.suchattai.tsukaima.ui.chat.components.CodeBlock
import labs.suchattai.tsukaima.ui.components.Chip
import labs.suchattai.tsukaima.ui.components.IconButton44
import labs.suchattai.tsukaima.ui.components.IconName
import labs.suchattai.tsukaima.ui.theme.TsukaimaTheme
import labs.suchattai.tsukaima.ui.theme.TsukaimaType
import labs.suchattai.tsukaima.ui.theme.tsukaima

private const val REFRESH_MS = 5_000L

/** About one log line: closer than this to the bottom still counts as following the tail. */
private val FOLLOW_SLACK = 24.dp

/** Read-only log tail. Auto-refreshes every 5 s while the screen is resumed. */
@Composable
fun LogsScreen(vm: LogsViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(vm, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(REFRESH_MS)
                vm.refresh()
            }
        }
    }
    LogsContent(state, onBack, vm::setFile, vm::setLevel)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LogsContent(
    state: LogsUiState,
    onBack: () -> Unit = {},
    onFile: (String) -> Unit = {},
    onLevel: (String?) -> Unit = {},
) {
    val colors = MaterialTheme.tsukaima
    val scroll = rememberScrollState()
    val text = state.text
    // Follow the tail only while the reader is at it (within about a line of the bottom), so a refresh never
    // yanks someone reading older lines; a new file or level starts at the bottom again.
    val slack = with(LocalDensity.current) { FOLLOW_SLACK.roundToPx() }
    var pinned by remember { mutableStateOf(true) }
    var shownFilter by remember { mutableStateOf(state.file to state.level) }
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) pinned = scroll.value >= scroll.maxValue - slack
        }
    }
    LaunchedEffect(text) {
        withFrameNanos { }
        val filter = state.file to state.level
        if (pinned || filter != shownFilter) {
            shownFilter = filter
            scroll.scrollTo(scroll.maxValue)
            pinned = true
        }
    }

    Column(Modifier.fillMaxSize().background(colors.surfacePage)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton44(IconName.ArrowBack, "Back", onBack)
            Text("Logs", style = TsukaimaType.title, color = colors.ink1)
        }
        // Wraps on a narrow screen or a large font instead of clipping the level chips.
        FlowRow(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            listOf("gateway", "agent").forEach { f ->
                Chip("$f.log", onClick = { onFile(f) }, selected = state.file == f)
            }
            Box(Modifier.width(1.dp).height(24.dp).background(colors.hairline))
            Chip("All", onClick = { onLevel(null) }, selected = state.level == null)
            Chip("Warn+", onClick = { onLevel(LEVEL_WARN) }, selected = state.level == LEVEL_WARN)
        }
        if (state.error != null) {
            Text(
                state.error,
                style = TsukaimaType.caption,
                color = colors.ink3,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 2.dp),
            )
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp)
                .verticalScroll(scroll),
        ) {
            CodeBlock(code = text, lang = "${state.file}.log · last $LOG_LINES", readOnly = true)
        }
    }
}

private val sample = listOf(
    "2026-10-20 14:02:11,001 INFO gateway.run: telegram poll ok (3 updates)\n",
    "2026-10-20 14:02:15,420 WARNING storage: token expires in 2 days\n",
    "2026-10-20 14:03:01,877 INFO cron: inbox-triage fired\n",
)

@Composable
private fun LogsPreview(dark: Boolean) {
    TsukaimaTheme(darkTheme = dark) {
        LogsContent(LogsUiState(lines = sample, error = "Couldn't load logs: HTTP 502"))
    }
}

@Preview(name = "Logs light", showBackground = true)
@Composable
private fun LogsLightPreview() = LogsPreview(dark = false)

@Preview(name = "Logs dark", showBackground = true)
@Composable
private fun LogsDarkPreview() = LogsPreview(dark = true)
