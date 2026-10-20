package labs.suchattai.tsukaima.ui.cron

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.json.JsonPrimitive
import labs.suchattai.tsukaima.data.api.CronJobDto
import labs.suchattai.tsukaima.data.api.CronScheduleDto
import labs.suchattai.tsukaima.domain.cron.CronHuman
import labs.suchattai.tsukaima.ui.components.ButtonSize
import labs.suchattai.tsukaima.ui.components.ConfirmDialog
import labs.suchattai.tsukaima.ui.components.IconButton44
import labs.suchattai.tsukaima.ui.components.IconName
import labs.suchattai.tsukaima.ui.components.MenuItem
import labs.suchattai.tsukaima.ui.components.MenuSheet
import labs.suchattai.tsukaima.ui.components.SecondaryButton
import labs.suchattai.tsukaima.ui.components.TsukaimaRow
import labs.suchattai.tsukaima.ui.theme.TsukaimaTheme
import labs.suchattai.tsukaima.ui.theme.TsukaimaType
import labs.suchattai.tsukaima.ui.theme.tsukaima
import java.time.Instant

/** Cron job list. [onEdit] receives a job to edit, or null for a new one; the caller navigates to the editor. */
@Composable
fun CronScreen(vm: CronViewModel, onBack: () -> Unit, onEdit: (CronJobDto?) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    CronContent(
        state = state,
        onBack = onBack,
        onNew = { vm.startEdit(null); onEdit(null) },
        onRun = vm::runNow,
        onPause = vm::pause,
        onResume = vm::resume,
        onEdit = { vm.startEdit(it); onEdit(it) },
        onDelete = vm::delete,
    )
}

@Composable
internal fun CronContent(
    state: CronUiState,
    onBack: () -> Unit = {},
    onNew: () -> Unit = {},
    onRun: (String) -> Unit = {},
    onPause: (String) -> Unit = {},
    onResume: (String) -> Unit = {},
    onEdit: (CronJobDto) -> Unit = {},
    onDelete: (String) -> Unit = {},
) {
    val colors = MaterialTheme.tsukaima
    var menuJob by remember { mutableStateOf<CronJobDto?>(null) }
    var askDelete by remember { mutableStateOf<CronJobDto?>(null) }
    val now = remember(state.jobs) { Instant.now() }

    Column(Modifier.fillMaxSize().background(colors.surfacePage)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton44(IconName.ArrowBack, "Back", onBack)
            Text("Cron", style = TsukaimaType.title, color = colors.ink1)
            Spacer(Modifier.weight(1f))
            SecondaryButton("New job", onNew, icon = IconName.Add, size = ButtonSize.Sm)
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            state.jobs.forEach { job ->
                val paused = job.isPaused
                TsukaimaRow(
                    title = job.title,
                    subtitle = job.expr.let { "${CronHuman.describe(it)} · $it" },
                    leading = if (paused) IconName.PauseCircle else IconName.Schedule,
                    trailing = {
                        val meta = if (paused) "paused" else CronHuman.nextRunLabel(job.nextRunAt, now)
                        if (meta.isNotEmpty()) Text(meta, style = TsukaimaType.caption, color = colors.ink3)
                    },
                    onClick = { menuJob = job },
                    onLongClick = { menuJob = job },
                    modifier = Modifier.alpha(if (paused) 0.6f else 1f),
                )
            }
            if (state.jobs.isEmpty() && !state.loading) {
                Text(
                    "No cron jobs yet. Tap New job to add one.",
                    style = TsukaimaType.caption,
                    color = colors.ink3,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            Text(
                "Jobs run on the Hermes host in its local time.",
                style = TsukaimaType.caption,
                color = colors.ink3,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp),
            )
        }
    }

    val picked = menuJob
    MenuSheet(
        visible = picked != null,
        onDismiss = { menuJob = null },
        items = if (picked == null) {
            emptyList()
        } else {
            listOf(
                MenuItem("Run now", IconName.PlayArrow, onClick = { onRun(picked.id) }),
                if (picked.isPaused) {
                    MenuItem("Resume", IconName.PlayCircle, onClick = { onResume(picked.id) })
                } else {
                    MenuItem("Pause", IconName.PauseCircle, onClick = { onPause(picked.id) })
                },
                MenuItem("Edit", IconName.Edit, onClick = { onEdit(picked) }),
                MenuItem("Delete", IconName.Delete, danger = true, onClick = { askDelete = picked }),
            )
        },
    )
    askDelete?.let { job ->
        ConfirmDialog(
            title = "Delete this job?",
            body = "“${job.title}” stops running and can’t be restored.",
            confirmText = "Delete",
            onConfirm = { onDelete(job.id); askDelete = null },
            onDismiss = { askDelete = null },
            danger = true,
        )
    }
}

private fun sample() = CronUiState(
    jobs = listOf(
        CronJobDto("1", "Nightly backup", "Run the backup", CronScheduleDto("cron", "0 3 * * *", "0 3 * * *"), enabled = true, state = "scheduled", nextRunAt = "2999-01-01T03:00:00+00:00", deliver = JsonPrimitive("local")),
        CronJobDto("2", "Inbox triage", "Summarize mail", CronScheduleDto("cron", "*/30 8-20 * * 1-5", null), enabled = true, state = "scheduled", nextRunAt = null),
        CronJobDto("3", "Paper digest", "Digest", CronScheduleDto("cron", "0 7 * * 1", null), enabled = false, state = "paused"),
    ),
)

@Composable
private fun CronPreview(dark: Boolean) = TsukaimaTheme(darkTheme = dark) { CronContent(sample()) }

@Preview(name = "Cron light", heightDp = 600)
@Composable
private fun CronLightPreview() = CronPreview(false)

@Preview(name = "Cron dark", heightDp = 600)
@Composable
private fun CronDarkPreview() = CronPreview(true)
