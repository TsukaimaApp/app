package app.tsukaima.ui.chat.components

import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import app.tsukaima.ui.theme.Dimens
import app.tsukaima.ui.theme.SourceSerif
import app.tsukaima.ui.theme.TsukaimaShapes
import app.tsukaima.ui.theme.TsukaimaType
import app.tsukaima.ui.theme.tsukaima
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * One reasoning effort of the picker: the [value] `config.set key=reasoning` takes, its [name], and how the status
 * line says it after "Hermes awaits," ([phrase]), with a light anime flavour: the user asked for that, and for not too much of it.
 * The line is a state, so it is as true in an empty chat as after a reply; "Hermes is listening, thinking hard"
 * (0.12) read as if it were thinking now.
 */
data class ReasoningLevel(val value: String, val name: String, val phrase: String)

/** The efforts in rising order (the values of `REASONING_LEVELS`); the index is the level's strength, 0 = off. */
val REASONING_PICKER: List<ReasoningLevel> = listOf(
    ReasoningLevel("none", "Off", "taking it easy"),
    ReasoningLevel("minimal", "Minimal", "barely lifting a finger"),
    ReasoningLevel("low", "Low", "just warming up"),
    ReasoningLevel("medium", "Medium", "getting serious"),
    ReasoningLevel("high", "High", "ready to go all out"),
    ReasoningLevel("xhigh", "Extra high", "holding nothing back"),
    ReasoningLevel("max", "Max", "at full power"),
    ReasoningLevel("ultra", "Ultra", "going beyond its limits"),
)

/** From this strength on (Max, Ultra) the phrase keeps shimmering; below it only moves when it is picked. */
internal const val TOP_LEVEL = 6

/** The strength of [effort] in [REASONING_PICKER]; -1 for the provider's default (`""`), an unknown value or none yet. */
internal fun reasoningStrength(effort: String?): Int = REASONING_PICKER.indexOfFirst { it.value == effort }

/** What the status line says for [effort]; the provider's default and an unknown value read "at its usual pace". */
internal fun reasoningPhrase(effort: String?): String = REASONING_PICKER.getOrNull(reasoningStrength(effort))?.phrase ?: "at its usual pace"

private val Gold = Color(0xFFF0D9A0)
private val Rainbow = listOf(
    Color(0xFFFF8FA3), Color(0xFFFFC47A), Color(0xFFF5F08A), Color(0xFF8FE3A8), Color(0xFF8FD0FF), Color(0xFFB79CFF), Color(0xFFFF8FD6), Color(0xFFFF8FA3),
)
private const val CAST_MS = 700
private const val SPARK_MS = 900
private const val RAINBOW_MS = 2200
private const val SPARKS = 10

/** Whether the system's "remove animations" setting is on (animator duration scale 0): nothing here moves then. */
@Composable
internal fun motionOff(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

/**
 * The chat's status line while it is the user's turn: the dot, "Hermes awaits," and the reasoning effort as a
 * phrase ("ready to go all out") that is the control for it. A tap on the phrase calls [onToggle] (the [ReasoningPanel]
 * under the line); a tap elsewhere on the line is [onClick], as on the plain status line. [meter] sits at the end.
 *
 * Motion: picking a level makes the new phrase condense (fade in from wide letter-spacing) and throws a burst of
 * sparks. Max and Ultra then open with about two seconds of rainbow across the phrase and keep a gold shimmer, a
 * twinkling star and a pulsing dot; lower levels are still, their dot glowing more with the level; Off is grey.
 */
@Composable
fun ReasoningStatusLine(
    effort: String?,
    open: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    lead: String = "Hermes awaits,",
    onClick: (() -> Unit)? = null,
    meter: String? = null,
    /** Shown at the line's end in the meter's place (the background-process chip). */
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.tsukaima
    val strength = reasoningStrength(effort)
    val still = motionOff()
    val top = strength >= TOP_LEVEL && !still
    val base = if (strength == 0) colors.ink2 else colors.accent

    // A pick, not the first composition, casts the spell.
    var casts by remember { mutableIntStateOf(0) }
    var seen by remember { mutableIntStateOf(strength) }
    val cast = remember { Animatable(1f) }
    val spark = remember { Animatable(1f) }
    val rainbow = remember { Animatable(1f) }
    LaunchedEffect(strength) {
        if (strength == seen) return@LaunchedEffect
        seen = strength
        casts++
    }
    LaunchedEffect(casts) {
        if (casts == 0 || still) return@LaunchedEffect
        cast.snapTo(0f)
        cast.animateTo(1f, tween(CAST_MS, easing = LinearOutSlowInEasing))
    }
    LaunchedEffect(casts) {
        if (casts == 0 || still) return@LaunchedEffect
        spark.snapTo(0f)
        spark.animateTo(1f, tween(SPARK_MS, easing = LinearOutSlowInEasing))
    }
    LaunchedEffect(casts) {
        if (casts == 0 || still || strength < TOP_LEVEL) return@LaunchedEffect rainbow.snapTo(1f)
        rainbow.snapTo(0f)
        rainbow.animateTo(1f, tween(RAINBOW_MS, easing = LinearEasing))
    }

    val loop = if (top) rememberInfiniteTransition(label = "reasoningTop") else null
    val sweep = loop?.animateFloat(0f, 1f, infiniteRepeatable(tween(if (strength > TOP_LEVEL) 2600 else 3600, easing = LinearEasing)), label = "shimmer")
    val beat = loop?.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "beat")

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .heightIn(min = Dimens.hit)
            .padding(horizontal = Dimens.gutter),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // The dot: grey when off, a glow that grows with the level, a pulsing ring at the top.
        Box(
            Modifier
                .size(7.dp)
                .drawBehind {
                    val dot = size.minDimension / 2
                    if (strength > 0) {
                        val pulse = beat?.value ?: 0f
                        val reach = dot + (1.dp.toPx() + strength * 0.9.dp.toPx()) * (if (top) 0.6f + pulse else 1f)
                        drawCircle(
                            Brush.radialGradient(listOf(colors.accent.copy(alpha = if (top) 0.55f - pulse * 0.3f else 0.35f), Color.Transparent), radius = reach),
                            radius = reach,
                        )
                    }
                    drawCircle(if (strength > 0) colors.accent else colors.ink3, radius = dot)
                },
        )
        // The lead gives way first on a narrow screen: the phrase is the control and stays whole.
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(lead, style = TsukaimaType.statusLine, color = colors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Row(
            modifier = Modifier
                .drawBehind {
                    // The burst: stars flying off the phrase, turning and fading as they go. Drawn here, outside the
                    // text's own layer, which would clip them.
                    val p = spark.value
                    if (p >= 1f) return@drawBehind
                    val fade = if (p < 0.25f) p / 0.25f else (1f - p) / 0.75f
                    repeat(SPARKS) { k ->
                        val angle = k / SPARKS.toFloat() * 2f * PI.toFloat() + 0.4f
                        val reach = (26 + k % 3 * 12 + strength.coerceAtLeast(0) * 2).dp.toPx() * p
                        val origin = Offset(size.width * (0.1f + k * 0.085f), size.height / 2)
                        val at = origin + Offset(cos(angle) * reach, sin(angle) * reach * 0.7f)
                        star(at, 5.dp.toPx() * (0.2f + 0.8f * p), 140f * p, (if (k % 2 == 0) colors.accent else if (strength >= TOP_LEVEL) Gold else Color.White).copy(alpha = fade))
                    }
                }
                .clickable(role = Role.Button, onClickLabel = "Change the reasoning level", onClick = onToggle)
                .heightIn(min = Dimens.hit)
                .semantics(mergeDescendants = true) {
                    contentDescription = "Reasoning level: ${REASONING_PICKER.getOrNull(strength)?.name ?: "Default"}"
                    stateDescription = if (open) "Picker open" else "Picker closed"
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = reasoningPhrase(effort),
                style = TsukaimaType.statusLine.copy(letterSpacing = (0.18f * (1f - cast.value)).em),
                color = base,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .graphicsLayer {
                        alpha = (cast.value / 0.6f).coerceAtMost(1f)
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        drawContent()
                        // Light across the letters only: SrcAtop paints where the text already is.
                        sweep?.let {
                            val x = size.width * (2f - 4f * it.value)
                            drawRect(
                                Brush.linearGradient(
                                    0.38f to Color.Transparent, 0.5f to (if (strength >= TOP_LEVEL) Gold else Color.White), 0.62f to Color.Transparent,
                                    start = Offset(x - size.width, 0f), end = Offset(x + size.width, size.height),
                                ),
                                blendMode = BlendMode.SrcAtop,
                            )
                        }
                        val r = rainbow.value
                        if (r < 1f) {
                            val fade = when {
                                r < 0.12f -> r / 0.12f
                                r > 0.75f -> (1f - r) / 0.25f
                                else -> 1f
                            }
                            val shift = size.width * 3f * r
                            drawRect(
                                Brush.linearGradient(Rainbow, start = Offset(-shift, 0f), end = Offset(size.width * 1.5f - shift, size.height), tileMode = TileMode.Repeated),
                                alpha = fade,
                                blendMode = BlendMode.SrcAtop,
                            )
                        }
                        // The dashed underline says "this can be changed".
                        val y = size.height - 1.dp.toPx()
                        drawLine(base, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())))
                    }
            )
            if (top) {
                Box(
                    Modifier
                        .size(12.dp)
                        .drawBehind {
                            val t = beat?.value ?: 1f
                            star(center, size.minDimension / 2 * (0.8f + 0.35f * t), 45f * t, Gold.copy(alpha = 0.35f + 0.65f * t))
                        },
                )
            }
        }
        }
        if (trailing != null) trailing() else if (meter != null) Text(meter, style = TsukaimaType.caption, color = colors.ink3, maxLines = 1)
    }
}

/** A four-pointed star of [radius] at [at], turned by [degrees]. */
private fun DrawScope.star(at: Offset, radius: Float, degrees: Float, color: Color) {
    val waist = radius * 0.22f
    val path = Path().apply {
        moveTo(0f, -radius); lineTo(waist, -waist); lineTo(radius, 0f); lineTo(waist, waist)
        lineTo(0f, radius); lineTo(-waist, waist); lineTo(-radius, 0f); lineTo(-waist, -waist); close()
    }
    translate(at.x, at.y) { rotate(degrees, Offset.Zero) { drawPath(path, color) } }
}

/**
 * The levels under the status line while [open]: two columns, each level with its name and phrase, the current one
 * filled and (when motion is on) breathing. It grows out of the phrase and its entries arrive one after another. A
 * pick calls [onPick] with the effort value; the caller closes the panel.
 */
@Composable
fun ReasoningPanel(open: Boolean, effort: String?, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    val still = motionOff()
    AnimatedVisibility(
        visible = open,
        modifier = modifier,
        enter = if (still) fadeIn(tween(0)) else expandVertically(tween(220)) + fadeIn(tween(220)) + scaleIn(tween(320, easing = FastOutSlowInEasing), initialScale = 0.86f, transformOrigin = TransformOrigin(0.45f, 0f)),
        exit = if (still) fadeOut(tween(0)) else shrinkVertically(tween(160)) + fadeOut(tween(120)),
    ) {
        val colors = MaterialTheme.tsukaima
        val shape = TsukaimaShapes.row
        val strength = reasoningStrength(effort)
        val breath = if (still) null else rememberInfiniteTransition(label = "reasoningPick").animateFloat(0f, 1f, infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath")
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 6.dp)
                .background(colors.surfaceRaised, shape)
                .border(1.dp, colors.hairline, shape)
                .padding(6.dp)
                .selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            REASONING_PICKER.chunked(2).forEachIndexed { row, pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    pair.forEachIndexed { column, level ->
                        val index = row * 2 + column
                        val here = index == strength
                        val arrive = remember { Animatable(if (still) 1f else 0f) }
                        LaunchedEffect(Unit) { arrive.animateTo(1f, tween(340, delayMillis = 50 + index * 35, easing = FastOutSlowInEasing)) }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .graphicsLayer {
                                    alpha = arrive.value
                                    translationY = (1f - arrive.value) * 8.dp.toPx()
                                    scaleX = 0.94f + 0.06f * arrive.value
                                    scaleY = scaleX
                                }
                                .clip(shape)
                                .then(
                                    if (here) {
                                        Modifier
                                            .background(colors.accentSelected)
                                            .drawBehind {
                                                val t = breath?.value ?: 0f
                                                drawRoundRect(
                                                    colors.accent.copy(alpha = 0.35f + 0.65f * t),
                                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()),
                                                    style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()),
                                                )
                                            }
                                    } else {
                                        Modifier
                                    },
                                )
                                .selectable(selected = here, role = Role.RadioButton) { onPick(level.value) }
                                .heightIn(min = 52.dp)
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(level.name, style = TsukaimaType.ui.copy(fontWeight = FontWeight.SemiBold), color = if (here) colors.ink1 else colors.ink2, maxLines = 1)
                            Text(
                                level.phrase,
                                style = TsukaimaType.caption.copy(fontFamily = SourceSerif, fontStyle = FontStyle.Italic),
                                color = if (here) colors.ink1 else colors.ink3,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReasoningPickerPreview(dark: Boolean) = PreviewPage(dark) {
    ReasoningStatusLine("high", open = false, onToggle = {}, meter = "9% ctx")
    ReasoningStatusLine("none", open = false, onToggle = {}, meter = "9% ctx")
    ReasoningStatusLine("ultra", open = true, onToggle = {}, meter = "9% ctx")
    ReasoningPanel(open = true, effort = "ultra", onPick = {})
}

@Preview(name = "ReasoningPicker light")
@Composable
private fun ReasoningPickerLightPreview() = ReasoningPickerPreview(dark = false)

@Preview(name = "ReasoningPicker dark")
@Composable
private fun ReasoningPickerDarkPreview() = ReasoningPickerPreview(dark = true)
