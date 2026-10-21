package app.tsukaima.ui.chat.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.tsukaima.domain.chat.ChatItem
import app.tsukaima.domain.chat.ClarifyAnswers
import app.tsukaima.domain.chat.ClarifyQuestion
import app.tsukaima.ui.components.Chip
import app.tsukaima.ui.components.GhostButton
import app.tsukaima.ui.components.IconName
import app.tsukaima.ui.components.PrimaryButton
import app.tsukaima.ui.components.TsukaimaTextField
import app.tsukaima.ui.components.pressWash
import app.tsukaima.ui.theme.Dimens
import app.tsukaima.ui.theme.Motion
import app.tsukaima.ui.theme.TsukaimaShapes
import app.tsukaima.ui.theme.TsukaimaType
import app.tsukaima.ui.theme.tsukaima

/** What the user has entered for one question: picked choices, the "Other…" field and whether it is open. */
private class Draft(val picked: MutableState<List<String>>, val other: MutableState<String>, val otherOpen: MutableState<Boolean>)

/**
 * Inline `clarify` request (same tier as [ApprovalCard]). Each question shows its choices as full-width rows
 * ([ChoiceRow]: one selected, or toggles for `multi_select`) plus "Other…" with a text field, or just a text field
 * when it has no choices. Question and choice text wraps: nothing agent-written is cut short here. Send carries every question (unanswered ones as null) and is enabled once one has an answer; Skip
 * cancels them all. Question and choice text is agent-controlled, so it goes through [CommandText.reveal].
 * Once [ChatItem.Clarify.answered], the card shows the answers read-only.
 */
@Composable
fun ClarifyCard(
    item: ChatItem.Clarify,
    onSend: (Map<String, String?>) -> Unit,
    onSkipAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.tsukaima
    FloatingCard(modifier) {
        CardHeader(IconName.Help, "Hermes has a question")
        val drafts = item.questions.associate { q -> q.qid to rememberDraft(item.id, q.qid) }
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item.questions.forEach { q ->
                val draft = drafts.getValue(q.qid)
                if (item.answered) AnsweredQuestion(q, item.answers[q.qid]) else QuestionInput(q, draft)
            }
        }
        val answers = item.questions.associate { q ->
            val d = drafts.getValue(q.qid)
            q.qid to ClarifyAnswers.answer(q, d.picked.value, if (d.otherOpen.value || q.choices == null) d.other.value else "")
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
        ) {
            if (item.answered) {
                Text(
                    text = if (item.answers.isEmpty()) "Skipped" else "Answered",
                    style = TsukaimaType.caption,
                    color = colors.ink3,
                    modifier = Modifier.weight(1f),
                )
            }
            GhostButton("Skip", onSkipAll, enabled = !item.answered)
            PrimaryButton(
                "Send",
                { onSend(answers) },
                icon = IconName.Check,
                enabled = !item.answered && answers.values.any { it != null },
            )
        }
    }
}

@Composable
private fun rememberDraft(cardId: String, qid: String): Draft {
    val picked = rememberSaveable(cardId, qid) { mutableStateOf(ArrayList<String>() as List<String>) }
    val other = rememberSaveable(cardId, qid) { mutableStateOf("") }
    val otherOpen = rememberSaveable(cardId, qid) { mutableStateOf(false) }
    return remember(picked, other, otherOpen) { Draft(picked, other, otherOpen) }
}

@Composable
private fun QuestionText(q: ClarifyQuestion) {
    val text = remember(q.question) { CommandText.reveal(q.question).text }
    Text(text, style = TsukaimaType.ui, color = MaterialTheme.tsukaima.ink1)
}

@Composable
private fun QuestionInput(q: ClarifyQuestion, draft: Draft) {
    var picked by draft.picked
    var other by draft.other
    var otherOpen by draft.otherOpen
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        QuestionText(q)
        val choices = q.choices
        if (choices != null) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                choices.forEach { choice ->
                    val label = remember(choice) { CommandText.reveal(choice).text }
                    ChoiceRow(label, selected = choice in picked, multi = q.multiSelect) {
                        picked = when {
                            q.multiSelect -> if (choice in picked) picked - choice else ArrayList(picked + choice)
                            else -> ArrayList(listOf(choice))
                        }
                        if (!q.multiSelect) otherOpen = false
                    }
                }
                ChoiceRow("Other…", selected = otherOpen, multi = q.multiSelect) {
                    otherOpen = !otherOpen
                    if (otherOpen && !q.multiSelect) picked = ArrayList()
                }
            }
        }
        if (choices == null || otherOpen) {
            TsukaimaTextField(
                value = other,
                onValueChange = { other = it },
                placeholder = if (choices == null) "Your answer" else "Something else",
                singleLine = false,
                maxLines = 3,
            )
        }
    }
}

/**
 * One pickable choice: a full-width row whose text wraps, so a long choice (Hermes allows thousands of characters)
 * is read in full, never cut to a chip. A dot (single) or square (multi) marks the selection; [selected] fills the
 * row with `accentSelected` like a selected [Chip].
 */
@Composable
private fun ChoiceRow(label: String, selected: Boolean, multi: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.tsukaima
    val shape = TsukaimaShapes.row
    val source = remember { MutableInteractionSource() }
    val fill by animateColorAsState(
        targetValue = if (selected) colors.accentSelected else Color.Transparent,
        animationSpec = tween(Motion.quick, easing = Motion.easing),
        label = "choiceFill",
    )
    val markShape = if (multi) RoundedCornerShape(4.dp) else CircleShape
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Dimens.hit)
            .clip(shape)
            .background(fill)
            .border(1.dp, if (selected) Color.Transparent else colors.hairline, shape)
            .pressWash(source, shape)
            .selectable(
                selected = selected,
                interactionSource = source,
                indication = null,
                role = if (multi) Role.Checkbox else Role.RadioButton,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(16.dp)
                .clip(markShape)
                .background(if (selected) colors.accent else Color.Transparent)
                .border(1.5.dp, if (selected) colors.accent else colors.ink3, markShape),
        )
        Text(
            label,
            style = TsukaimaType.ui,
            color = if (selected) colors.ink1 else colors.ink2,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun AnsweredQuestion(q: ClarifyQuestion, answer: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        QuestionText(q)
        val shown = remember(answer, q.multiSelect) { CommandText.reveal(ClarifyAnswers.display(answer, q.multiSelect)).text }
        Text(
            text = shown,
            style = TsukaimaType.body,
            color = if (answer == null) MaterialTheme.tsukaima.ink3 else MaterialTheme.tsukaima.ink2,
        )
    }
}

private val sampleQuestions = listOf(
    ClarifyQuestion(
        "q1", "Which colour do you prefer?",
        listOf("red (Recommended) — the warm one that matches the existing accent and keeps the contrast ratio above 4.5:1", "green", "blue"),
        multiSelect = false,
    ),
    ClarifyQuestion("q2", "Which toppings?", listOf("ham", "olives", "pineapple"), multiSelect = true),
    ClarifyQuestion("q3", "Anything else I should know?", null, multiSelect = false),
)

@Composable
private fun ClarifyPreview(dark: Boolean) = PreviewPage(dark) {
    ClarifyCard(ChatItem.Clarify("srq-1", sampleQuestions, answered = false), {}, {})
    ClarifyCard(
        ChatItem.Clarify(
            "srq-2", sampleQuestions, answered = true,
            answers = mapOf("q1" to "green", "q2" to """["ham","olives"]""", "q3" to null),
        ),
        {}, {},
    )
}

@Preview(name = "ClarifyCard light", heightDp = 900)
@Composable
private fun ClarifyLightPreview() = ClarifyPreview(dark = false)

@Preview(name = "ClarifyCard dark", heightDp = 900)
@Composable
private fun ClarifyDarkPreview() = ClarifyPreview(dark = true)
