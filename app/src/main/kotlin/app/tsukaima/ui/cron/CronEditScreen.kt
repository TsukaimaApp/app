package app.tsukaima.ui.cron

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tsukaima.data.api.DeliveryTargetDto
import app.tsukaima.ui.components.ButtonSize
import app.tsukaima.ui.components.Chip
import app.tsukaima.ui.components.IconButton44
import app.tsukaima.ui.components.IconName
import app.tsukaima.ui.components.PrimaryButton
import app.tsukaima.ui.components.TsukaimaTextField
import app.tsukaima.ui.theme.TsukaimaShapes
import app.tsukaima.ui.theme.TsukaimaTheme
import app.tsukaima.ui.theme.TsukaimaType
import app.tsukaima.ui.theme.tsukaima

/**
 * Full-screen job editor. Expects [CronViewModel.startEdit] to have run (CronScreen does that before
 * navigating); if the editor is not open on entry (state lost), it closes straight away.
 */
@Composable
fun CronEditScreen(vm: CronViewModel, onClose: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) {
        if (!vm.state.value.editorOpen) onClose()
    }
    LaunchedEffect(vm) { vm.saved.collect { onClose() } }
    CronEditContent(
        state = state,
        onClose = { vm.closeEditor(); onClose() },
        onSave = vm::save,
        onName = { vm.updateDraft(name = it) },
        onPrompt = { vm.updateDraft(prompt = it) },
        onSchedule = { vm.updateDraft(schedule = it) },
        onDeliver = { vm.updateDraft(deliver = it) },
    )
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun CronEditContent(
    state: CronUiState,
    onClose: () -> Unit = {},
    onSave: () -> Unit = {},
    onName: (String) -> Unit = {},
    onPrompt: (String) -> Unit = {},
    onSchedule: (String) -> Unit = {},
    onDeliver: (String) -> Unit = {},
) {
    val colors = MaterialTheme.tsukaima
    val d = state.draft
    // A job may deliver somewhere the server no longer lists; keep it selectable so saving does not silently change it.
    val choices = state.deliveryChoices.let { c ->
        if (c.any { it.id == d.deliver }) c else c + DeliveryTargetDto(d.deliver, d.deliver)
    }
    Column(Modifier.fillMaxSize().background(colors.surfacePage)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton44(IconName.Close, "Cancel", onClose)
            Text(if (state.isNew) "New job" else "Edit job", style = TsukaimaType.title, color = colors.ink1)
            Spacer(Modifier.weight(1f))
            PrimaryButton("Save", onSave, size = ButtonSize.Sm, enabled = state.canSave)
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            TsukaimaTextField(d.name, onName, label = "Name", placeholder = "Nightly backup")
            TsukaimaTextField(
                d.prompt, onPrompt,
                label = "Prompt",
                placeholder = "What should the agent do when this fires?",
                singleLine = false,
                minLines = 5,
            )
            if (state.scheduleEditable) {
                TsukaimaTextField(
                    d.schedule, onSchedule,
                    label = "Schedule",
                    mono = true,
                    helper = "Cron expression, server time. Example: 0 9 * * 1-5",
                    error = state.scheduleError,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("SCHEDULE", style = TsukaimaType.label, color = colors.ink3)
                    Text(
                        d.schedule,
                        style = TsukaimaType.mono,
                        color = colors.ink2,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(colors.surfaceSunken, TsukaimaShapes.input)
                            .border(1.dp, colors.hairline, TsukaimaShapes.input)
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    )
                    Text("Interval schedule — change it in the dashboard", style = TsukaimaType.caption, color = colors.ink3)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("DELIVER TO", style = TsukaimaType.label, color = colors.ink3)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    choices.forEach { t ->
                        Chip(t.name ?: t.id, onClick = { onDeliver(t.id) }, selected = d.deliver == t.id)
                    }
                }
            }
        }
    }
}

private fun sample(isNew: Boolean, schedule: String = "0 9 * * 1-5") = CronUiState(
    editorOpen = true,
    isNew = isNew,
    targets = listOf(DeliveryTargetDto("local", "Local (save only)"), DeliveryTargetDto("bot-chat:default", "Bot Chat (default)")),
    draft = CronDraft("Morning digest", "Summarize overnight mail and flag anything urgent.", schedule, "bot-chat:default"),
)

@Composable
private fun EditPreview(dark: Boolean, state: CronUiState) = TsukaimaTheme(darkTheme = dark) { CronEditContent(state) }

@Preview(name = "Cron edit light", heightDp = 700)
@Composable
private fun EditLightPreview() = EditPreview(false, sample(isNew = false))

@Preview(name = "Cron edit dark", heightDp = 700)
@Composable
private fun EditDarkPreview() = EditPreview(true, sample(isNew = false))

@Preview(name = "Cron edit error dark", heightDp = 700)
@Composable
private fun EditErrorPreview() = EditPreview(true, sample(isNew = true, schedule = "0 9 * *"))
