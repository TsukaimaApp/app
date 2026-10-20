package labs.suchattai.tsukaima.ui.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import labs.suchattai.tsukaima.R
import labs.suchattai.tsukaima.ui.components.PrimaryButton
import labs.suchattai.tsukaima.ui.theme.TsukaimaTheme
import labs.suchattai.tsukaima.ui.theme.TsukaimaType
import labs.suchattai.tsukaima.ui.theme.tsukaima

/**
 * First-run screen (HANDOFF §1, `onboarding-screen.jsx`). Spacing follows the jsx: a 10dp column gap plus the
 * element's own margin, so mark to wordmark is 24dp, status to caption 16dp, caption to button 32dp and
 * button to wisp 50dp. The wisp is capped at 18% of the screen height like the jsx's `maxHeight: 18vh`.
 */
@Composable
fun OnboardingScreen(onConnect: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.tsukaima
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    Box(
        modifier
            .fillMaxSize()
            .background(colors.surfacePage),
    ) {
        // writing-mode: vertical-rl for three upright glyphs is just a stacked column.
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 48.dp, end = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            "使い魔".forEach { Text(it.toString(), style = TsukaimaType.kanji, color = colors.ink3) }
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.mark),
                contentDescription = null,
                colorFilter = ColorFilter.tint(colors.accent),
                modifier = Modifier.height(72.dp),
            )
            Text(
                "Tsukaima",
                style = TsukaimaType.wordmarkLarge,
                color = colors.ink1,
                modifier = Modifier.padding(top = 24.dp),
            )
            Text(
                "Your familiar is listening.",
                style = TsukaimaType.statusLine,
                color = colors.ink2,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp),
            )
            Text(
                "Connect to your Hermes instance to start talking to your agent.",
                style = TsukaimaType.caption,
                color = colors.ink3,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(top = 16.dp)
                    .widthIn(max = 260.dp),
            )
            PrimaryButton("Connect", onClick = onConnect, modifier = Modifier.padding(top = 32.dp))
            Image(
                painter = painterResource(R.drawable.resting_wisp),
                contentDescription = null,
                colorFilter = ColorFilter.tint(colors.ink3.copy(alpha = 0.8f)),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .padding(top = 50.dp)
                    .width(180.dp)
                    .heightIn(max = screenHeight * 0.18f),
            )
        }
    }
}

@Preview(name = "Onboarding light", widthDp = 390, heightDp = 780)
@Composable
private fun OnboardingLightPreview() = TsukaimaTheme(darkTheme = false) { OnboardingScreen({}) }

@Preview(name = "Onboarding dark", widthDp = 390, heightDp = 780)
@Composable
private fun OnboardingDarkPreview() = TsukaimaTheme(darkTheme = true) { OnboardingScreen({}) }
