package app.tsukaima.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import app.tsukaima.ui.theme.TsukaimaShapes
import app.tsukaima.ui.theme.TsukaimaTheme
import app.tsukaima.ui.theme.TsukaimaType
import app.tsukaima.ui.theme.tsukaima

/**
 * Confirm dialog for destructive or gateway actions: serif [title], sans [body], an optional
 * [content] slot (e.g. a text field for rename), then Cancel + confirm. [danger] renders the
 * confirm action as a red-ink ghost button; otherwise it is the primary pill.
 *
 * The platform window dim is replaced by a full-screen scrim drawn with the theme's `scrim`
 * token so light/dark scrims match the rest of the app.
 */
@Composable
fun ConfirmDialog(
    title: String,
    body: String?,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean = false,
    content: @Composable () -> Unit = {},
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        (LocalView.current.parent as? DialogWindowProvider)?.window?.let { window ->
            SideEffect { window.setDimAmount(0f) }
        }
        ConfirmDialogContent(title, body, confirmText, onConfirm, onDismiss, danger, content)
    }
}

@Composable
internal fun ConfirmDialogContent(
    title: String,
    body: String?,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.tsukaima
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.scrim)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            .padding(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 340.dp)
                .fillMaxWidth()
                .floatShadow(TsukaimaShapes.card)
                .background(colors.surfaceRaised, TsukaimaShapes.card)
                // Swallow taps so they do not fall through to the scrim.
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = {})
                .padding(22.dp),
        ) {
            Text(title, style = TsukaimaType.titleSmall, color = colors.ink1)
            if (body != null) {
                Text(
                    body,
                    style = TsukaimaType.body.copy(fontSize = 14.5.sp),
                    color = colors.ink2,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Column(Modifier.padding(top = if (body != null) 20.dp else 12.dp)) { content() }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                GhostButton("Cancel", onDismiss)
                if (danger) {
                    GhostButton(confirmText, onConfirm, tone = Tone.Danger)
                } else {
                    PrimaryButton(confirmText, onConfirm)
                }
            }
        }
    }
}

@Composable
private fun DialogPreview(dark: Boolean) {
    TsukaimaTheme(darkTheme = dark) {
        Box(Modifier.background(MaterialTheme.tsukaima.surfacePage)) {
            ConfirmDialogContent(
                title = "Delete session?",
                body = "This can't be undone.",
                confirmText = "Delete",
                onConfirm = {},
                onDismiss = {},
                danger = true,
                content = {},
            )
        }
    }
}

@Preview(name = "Dialog light", heightDp = 360)
@Composable
private fun DialogLightPreview() = DialogPreview(dark = false)

@Preview(name = "Dialog dark", heightDp = 360)
@Composable
private fun DialogDarkPreview() = DialogPreview(dark = true)
