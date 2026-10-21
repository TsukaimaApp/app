package app.tsukaima.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.tsukaima.ui.theme.Dimens
import app.tsukaima.ui.theme.TsukaimaShapes
import app.tsukaima.ui.theme.TsukaimaTheme
import app.tsukaima.ui.theme.TsukaimaType
import app.tsukaima.ui.theme.tsukaima

/**
 * Raised cream card with a hairline border and 16dp inner padding. [elevated] adds the soft
 * float shadow, reserved for the composer/approval tier.
 */
@Composable
fun RaisedCard(
    modifier: Modifier = Modifier,
    elevated: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.tsukaima
    val shape = TsukaimaShapes.card
    Column(
        modifier = modifier
            .then(if (elevated) Modifier.floatShadow(shape) else Modifier)
            .background(colors.surfaceRaised, shape)
            .border(1.dp, colors.hairline, shape)
            .padding(16.dp),
        content = content,
    )
}

/** The design's float shadow (`--shadow-float`): lighter and warmer in light, deeper in dark. */
@Composable
internal fun Modifier.floatShadow(shape: Shape): Modifier {
    val dark = MaterialTheme.tsukaima.surfacePage.luminance() < 0.5f
    val color = if (dark) Dimens.shadowColorDark else Dimens.shadowColorLight
    return shadow(Dimens.shadowElevation, shape, clip = false, ambientColor = color, spotColor = color)
}

@Composable
private fun CardPreview(dark: Boolean) {
    TsukaimaTheme(darkTheme = dark) {
        Column(
            Modifier
                .background(MaterialTheme.tsukaima.surfacePage)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            RaisedCard {
                Text("Settings group", style = TsukaimaType.ui, color = MaterialTheme.tsukaima.ink1)
            }
            RaisedCard(elevated = true) {
                Text("Approval content", style = TsukaimaType.ui, color = MaterialTheme.tsukaima.ink1)
            }
        }
    }
}

@Preview(name = "Card light")
@Composable
private fun CardLightPreview() = CardPreview(dark = false)

@Preview(name = "Card dark")
@Composable
private fun CardDarkPreview() = CardPreview(dark = true)
