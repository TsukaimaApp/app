package app.tsukaima.ui.chat.components

import android.content.Context
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import app.tsukaima.domain.markdown.MathSafety
import app.tsukaima.domain.markdown.MdSpan
import app.tsukaima.domain.markdown.TexSpeech
import app.tsukaima.ui.components.IconButton44
import app.tsukaima.ui.components.IconName
import app.tsukaima.ui.theme.Dimens
import app.tsukaima.ui.theme.TsukaimaShapes
import app.tsukaima.ui.theme.TsukaimaType
import app.tsukaima.ui.theme.tsukaima
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.noties.jlatexmath.JLatexMathAndroid
import ru.noties.jlatexmath.JLatexMathDrawable

/** Display formulas are drawn this much larger than body text. */
private const val DisplayScale = 1.15f

/** A formula bigger than this many pixels is not drawn (it shows as its TeX source instead). */
private const val MaxPixels = 2_000_000

/** An inline formula wider than its line shrinks down to this share of the text size before it is clipped. */
private const val MinInlineScale = 0.7f

/** Each further try at fitting a formula draws it this much smaller. */
private const val FitStep = 0.95f

/** No width limit: display formulas scroll, and the dialog shows a formula whole. */
private const val Unbounded = Int.MAX_VALUE

/**
 * [tex] at [px] in [argb], fitted to [maxWidth] pixels: an over-wide formula is drawn smaller, down to
 * [MinInlineScale] of [px], and is clipped if still too wide.
 */
private data class TexKey(val tex: String, val px: Float, val argb: Int, val maxWidth: Int)

/**
 * A drawn formula, or a failed one ([bitmap] null), so a bad formula is not re-parsed on every streaming delta.
 * [clipped]: wider than its key's width even at the smallest size. [whole]: drawn at its key's size, not shrunk.
 */
private class TexResult(val bitmap: ImageBitmap?, val clipped: Boolean = false, val whole: Boolean = false)

private val Refused = TexResult(null)

/** Formulas drawn so far, by size in bytes (failures count one), so re-parsing a streaming reply does not redraw them. */
private val texCache = object : LruCache<TexKey, TexResult>(16 * 1024 * 1024) {
    override fun sizeOf(key: TexKey, value: TexResult): Int = value.bitmap?.asAndroidBitmap()?.byteCount ?: 1
}

/**
 * JLaTeXMath keeps process-global parser and font tables that are not safe to fill from two threads, so every
 * formula is drawn here, one at a time, off the main thread.
 */
private val texDispatcher = Dispatchers.Default.limitedParallelism(1)

/** Whether [JLatexMathAndroid.init] has run; read and written only on [texDispatcher]. */
private var texReady = false

/** [key]'s result when it is known without drawing: [Refused] by [MathSafety], or cached; null means a draw is due. */
private fun knownTex(key: TexKey): TexResult? = if (!MathSafety.allowed(key.tex)) Refused else cachedTex(key)

/**
 * [key]'s cached result; for a width-limited key, also the formula drawn whole at that size when it fits the width
 * (so a formula that fits is not redrawn when its paragraph's width changes, as on rotation).
 */
private fun cachedTex(key: TexKey): TexResult? {
    texCache.get(key)?.let { return it }
    if (key.maxWidth == Unbounded) return null
    val whole = texCache.get(key.copy(maxWidth = Unbounded)) ?: return null
    val bitmap = whole.bitmap ?: return null
    return whole.takeIf { bitmap.width <= key.maxWidth }
}

/** Draws [key] (on [texDispatcher]) and caches the outcome, failures included. */
private fun drawTex(context: Context, key: TexKey): TexResult {
    if (!MathSafety.allowed(key.tex)) return Refused // callers check first; this draw path never trusts that
    cachedTex(key)?.let { return it } // another paragraph with the same formula may have drawn it meanwhile
    val result = runCatching { fitTex(context, key) }.getOrNull() ?: TexResult(null)
    texCache.put(key, result)
    // Drawn whole, it is also the formula with no width limit: reusable at any width it fits.
    if (result.bitmap != null && result.whole && key.maxWidth != Unbounded) {
        texCache.put(key.copy(maxWidth = Unbounded), result)
    }
    return result
}

private fun fitTex(context: Context, key: TexKey): TexResult? {
    if (!texReady) {
        // The library's init provider does this at startup; this covers hosts without it (and publishes the context
        // to this dispatcher's thread).
        JLatexMathAndroid.init(context)
        texReady = true
    }
    fun build(px: Float) = JLatexMathDrawable.builder(key.tex).textSize(px).color(key.argb).build()
    var drawable = build(key.px)
    val whole = drawable.intrinsicWidth <= key.maxWidth
    if (!whole) {
        val floor = key.px * MinInlineScale
        var px = maxOf(floor, key.px * key.maxWidth / drawable.intrinsicWidth)
        drawable = build(px)
        // Glyph widths round, so the proportional size can still come out a pixel or two too wide.
        while (drawable.intrinsicWidth > key.maxWidth && px > floor) {
            px = maxOf(floor, px * FitStep)
            drawable = build(px)
        }
    }
    val w = drawable.intrinsicWidth
    val h = drawable.intrinsicHeight
    if (w <= 0 || h <= 0 || w.toLong() * h > MaxPixels) return null
    return TexResult(drawable.toBitmap(w, h).asImageBitmap(), clipped = w > key.maxWidth, whole = whole)
}

/**
 * [key]'s formula: a cached (or refused) one at once, in this composition, so a formula already drawn never flickers;
 * otherwise null until it has been drawn off the main thread.
 */
@Composable
private fun rememberTexResult(key: TexKey): TexResult? {
    val known = remember(key) { knownTex(key) }
    if (known != null) return known
    val context = LocalContext.current.applicationContext
    var drawn by remember(key) { mutableStateOf<TexResult?>(null) }
    LaunchedEffect(key) { drawn = withContext(texDispatcher) { drawTex(context, key) } }
    return drawn
}

/** The accessibility label of the formula [tex]. */
internal fun formulaDescription(tex: String): String =
    TexSpeech.readable(tex).let { if (it.isEmpty()) "Formula" else "Formula: $it" }

/** One line of [style], in sp (an unspecified line height counts as 1.2 em), for a formula still being drawn. */
private fun lineHeight(style: TextStyle): TextUnit = when {
    style.lineHeight.isSp -> style.lineHeight
    style.lineHeight.isEm -> style.fontSize * style.lineHeight.value
    else -> style.fontSize * 1.2f
}

/** The inline-content id of the formula [tex] in an annotated string. */
internal fun mathId(tex: String): String = "math:$tex"

/**
 * Inline images for the [MdSpan.Math] spans in [spans], keyed by [mathId], drawn at [style]'s size and fitted to
 * [maxWidth] pixels (the paragraph's width): wider ones shrink to fit, down to 70 % of the text size, then are clipped
 * with a "…" chip that opens the whole formula. While a formula is drawn off the main thread its slot is an empty
 * line-high box about as wide as its source; a formula missing from the map does not draw and is shown as code.
 */
@Composable
internal fun rememberMathContent(
    spans: List<MdSpan>,
    style: TextStyle,
    color: Color,
    maxWidth: Int = Unbounded,
): Map<String, InlineTextContent> {
    if (spans.none { it is MdSpan.Math }) return emptyMap()
    val density = LocalDensity.current
    val px = with(density) { style.fontSize.toPx() }
    val argb = color.toArgb()
    val texs = remember(spans) { spans.filterIsInstance<MdSpan.Math>().map { it.tex }.distinct() }
    val results = texs.map { tex -> key(tex) { tex to rememberTexResult(TexKey(tex, px, argb, maxWidth)) } }
    return remember(results, density, style, color, maxWidth) {
        results.mapNotNull { (tex, result) ->
            val content = when {
                result == null -> pendingContent(tex, style, px, maxWidth, density)
                result.bitmap == null -> return@mapNotNull null
                result.clipped -> clippedContent(tex, result.bitmap, style, color, maxWidth, density)
                else -> drawnContent(tex, result.bitmap, density)
            }
            mathId(tex) to content
        }.toMap()
    }
}

/** An empty box one line high and half an em per source character wide (at most [maxWidth]). */
private fun pendingContent(tex: String, style: TextStyle, px: Float, maxWidth: Int, density: Density): InlineTextContent {
    val width = minOf(tex.length * 0.5f * px, maxWidth.toFloat()).coerceAtLeast(1f)
    val placeholder = with(density) {
        Placeholder(width.toSp(), lineHeight(style), PlaceholderVerticalAlign.TextCenter)
    }
    return InlineTextContent(placeholder) { Box(Modifier.fillMaxSize()) }
}

private fun drawnContent(tex: String, bitmap: ImageBitmap, density: Density): InlineTextContent {
    val placeholder = with(density) {
        Placeholder(bitmap.width.toSp(), bitmap.height.toSp(), PlaceholderVerticalAlign.TextCenter)
    }
    return InlineTextContent(placeholder) {
        Image(bitmap = bitmap, contentDescription = formulaDescription(tex), modifier = Modifier.fillMaxSize())
    }
}

/**
 * The whole line: the formula's start, clipped, then a 44dp "…" chip that opens it full size ([FormulaDialog]). At
 * least 44dp high so the chip is a full target.
 */
private fun clippedContent(
    tex: String,
    bitmap: ImageBitmap,
    style: TextStyle,
    color: Color,
    maxWidth: Int,
    density: Density,
): InlineTextContent {
    val placeholder = with(density) {
        // A pixel short of the line, so rounding through sp never pushes it onto a line of its own.
        val height = maxOf(bitmap.height.toFloat(), Dimens.hit.toPx())
        Placeholder((maxWidth - 1).toSp(), height.toSp(), PlaceholderVerticalAlign.TextCenter)
    }
    return InlineTextContent(placeholder) {
        var open by rememberSaveable(tex) { mutableStateOf(false) }
        val colors = MaterialTheme.tsukaima
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).fillMaxHeight().clipToBounds(), contentAlignment = Alignment.CenterStart) {
                Image(
                    bitmap = bitmap,
                    contentDescription = formulaDescription(tex),
                    contentScale = ContentScale.None,
                    alignment = Alignment.CenterStart,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                Modifier
                    .width(Dimens.hit)
                    .fillMaxHeight()
                    .clickable(onClick = { open = true })
                    .semantics {
                        contentDescription = "Show formula"
                        role = Role.Button
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "…",
                    style = style,
                    color = colors.accent,
                    modifier = Modifier.background(colors.surfaceSunken, TsukaimaShapes.pill).padding(horizontal = 6.dp),
                )
            }
        }
        if (open) FormulaDialog(tex, style, color, onDismiss = { open = false })
    }
}

/** The formula [tex] at full [style] size, scrolling sideways, under a "Formula" title with a 44dp close button. */
@Composable
private fun FormulaDialog(tex: String, style: TextStyle, color: Color, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val colors = MaterialTheme.tsukaima
        val px = with(LocalDensity.current) { style.fontSize.toPx() }
        val result = rememberTexResult(TexKey(tex, px, color.toArgb(), Unbounded))
        Box(
            Modifier
                .fillMaxSize()
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(colors.surfaceRaised, TsukaimaShapes.card)
                    // Swallow taps so they do not reach the scrim (no click action for accessibility to announce).
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(start = 18.dp, end = 4.dp, top = 4.dp, bottom = 18.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Formula", style = TsukaimaType.titleSmall, color = colors.ink1, modifier = Modifier.weight(1f))
                    IconButton44(IconName.Close, contentDescription = "Close", onClick = onDismiss)
                }
                Box(
                    Modifier
                        .padding(end = 14.dp, top = 8.dp)
                        .horizontalScroll(rememberScrollState()),
                ) {
                    val bitmap = result?.bitmap
                    when {
                        bitmap != null -> Image(bitmap = bitmap, contentDescription = formulaDescription(tex))
                        result == null -> Box(Modifier.height(with(LocalDensity.current) { lineHeight(style).toDp() }))
                        else -> Text(tex, style = TsukaimaType.mono, color = colors.ink1)
                    }
                }
            }
        }
    }
}

/**
 * A display formula, centred; wider than the column, it scrolls sideways. A formula JLatexMath cannot draw (or
 * [MathSafety] refuses) shows its TeX source as a code block. Until it is drawn (off the main thread) it holds one
 * line of its height.
 */
@Composable
fun MathBlock(tex: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.tsukaima
    val density = LocalDensity.current
    val size = TsukaimaType.body.fontSize * DisplayScale
    val px = with(density) { size.toPx() }
    val result = rememberTexResult(TexKey(tex, px, colors.ink1.toArgb(), Unbounded))
    if (result == null) {
        val line = with(density) { (lineHeight(TsukaimaType.body) * DisplayScale).toDp() }
        Box(modifier.fillMaxWidth().padding(vertical = 4.dp).height(line))
        return
    }
    val bitmap = result.bitmap
    if (bitmap == null) {
        CodeBlock(code = tex, lang = "tex", modifier = modifier)
        return
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val column = maxWidth
        Box(Modifier.horizontalScroll(rememberScrollState())) {
            Box(Modifier.widthIn(min = column).padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                Image(bitmap = bitmap, contentDescription = formulaDescription(tex))
            }
        }
    }
}
