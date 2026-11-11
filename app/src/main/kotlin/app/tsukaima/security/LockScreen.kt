package app.tsukaima.security

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tsukaima.R
import app.tsukaima.ui.components.IconName
import app.tsukaima.ui.components.PrimaryButton
import app.tsukaima.ui.theme.TsukaimaType
import app.tsukaima.ui.theme.tsukaima

/**
 * Drawn over the whole app while it is locked: the mark, "Tsukaima is locked", and Unlock. The phone's prompt opens
 * by itself when the screen appears; Unlock opens it again after a cancel. Nothing under it can be touched.
 */
@Composable
fun LockScreen(onUnlocked: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.tsukaima
    val activity = LocalActivity.current
    val ask = { activity?.let { AppLock.authenticate(it, "Unlock Tsukaima") { ok -> if (ok) onUnlocked() } } }
    LaunchedEffect(Unit) { ask() }
    Box(
        modifier
            .fillMaxSize()
            .background(colors.surfacePage)
            // Swallows every touch: the app under it must not react.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 360.dp).padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Image(painterResource(R.drawable.mark), contentDescription = null, modifier = Modifier.size(72.dp))
            Text("Tsukaima is locked", style = TsukaimaType.title, color = colors.ink1, modifier = Modifier.semantics { heading() })
            Text(
                "Your chats and approvals stay hidden until you unlock.",
                style = TsukaimaType.body, color = colors.ink2, textAlign = TextAlign.Center,
            )
            PrimaryButton("Unlock", onClick = { ask() }, icon = IconName.Lock)
        }
    }
}
