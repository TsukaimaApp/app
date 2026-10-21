package labs.suchattai.tsukaima.ui.chat.components

import android.content.Context
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import labs.suchattai.tsukaima.domain.markdown.MdSpan
import labs.suchattai.tsukaima.ui.theme.TsukaimaType
import labs.suchattai.tsukaima.ui.theme.tsukaima
import ru.noties.jlatexmath.JLatexMathAndroid
import ru.noties.jlatexmath.JLatexMathDrawable

/** Display formulas are drawn this much larger than body text. */
private const val DisplayScale = 1.15f

/** A formula bigger than this many pixels is not drawn (it shows as its TeX source instead). */
private const val MaxPixels = 2_000_000

private data class TexKey(val tex: String, val px: Float, val argb: Int)

/** A drawn formula, or a failed one ([bitmap] null), so a bad formula is not re-parsed on every streaming delta. */
private class TexResult(val bitmap: ImageBitmap?)

/** Formulas drawn so far, by size in bytes (failures count one), so re-parsing a streaming reply does not redraw them. */
private val texCache = object : LruCache<TexKey, TexResult>(16 * 1024 * 1024) {
    override fun sizeOf(key: TexKey, value: TexResult): Int = value.bitmap?.asAndroidBitmap()?.byteCount ?: 1
}

/** [tex] drawn by JLatexMath at [px] text size in [argb], or null when it does not parse or is too big to draw. */
private fun texBitmap(context: Context, tex: String, px: Float, argb: Int): ImageBitmap? {
    val key = TexKey(tex, px, argb)
    texCache.get(key)?.let { return it.bitmap }
    val bitmap = runCatching {
        // The library's init provider does this at startup; repeating it is a field write and covers hosts without it.
        JLatexMathAndroid.init(context)
        val drawable = JLatexMathDrawable.builder(tex).textSize(px).color(argb).build()
        val w = drawable.intrinsicWidth
        val h = drawable.intrinsicHeight
        if (w <= 0 || h <= 0 || w.toLong() * h > MaxPixels) null else drawable.toBitmap(w, h).asImageBitmap()
    }.getOrNull()
    texCache.put(key, TexResult(bitmap))
    return bitmap
}

/** [tex] drawn at [size] in [color], remembered per input; null when it cannot be drawn. */
@Composable
private fun rememberTex(tex: String, size: TextUnit, color: Color): ImageBitmap? {
    val context = LocalContext.current.applicationContext
    val px = with(LocalDensity.current) { size.toPx() }
    return remember(tex, px, color) { texBitmap(context, tex, px, color.toArgb()) }
}

/** The inline-content id of the formula [tex] in an annotated string. */
internal fun mathId(tex: String): String = "math:$tex"

/**
 * Inline images for the [MdSpan.Math] spans in [spans] that draw, keyed by [mathId], each placeholder sized to its
 * formula at [style]'s size. A formula missing from the map does not draw and is shown as code instead.
 */
@Composable
internal fun rememberMathContent(spans: List<MdSpan>, style: TextStyle, color: Color): Map<String, InlineTextContent> {
    if (spans.none { it is MdSpan.Math }) return emptyMap()
    val context = LocalContext.current.applicationContext
    val density = LocalDensity.current
    val px = with(density) { style.fontSize.toPx() }
    return remember(spans, px, color, density) {
        spans.filterIsInstance<MdSpan.Math>().map { it.tex }.distinct().mapNotNull { tex ->
            val bitmap = texBitmap(context, tex, px, color.toArgb()) ?: return@mapNotNull null
            val placeholder = with(density) {
                Placeholder(bitmap.width.toSp(), bitmap.height.toSp(), PlaceholderVerticalAlign.TextCenter)
            }
            mathId(tex) to InlineTextContent(placeholder) {
                Image(bitmap = bitmap, contentDescription = tex, modifier = Modifier.fillMaxSize())
            }
        }.toMap()
    }
}

/**
 * A display formula, centred; wider than the column, it scrolls sideways. A formula JLatexMath cannot draw shows
 * its TeX source as a code block.
 */
@Composable
fun MathBlock(tex: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.tsukaima
    val bitmap = rememberTex(tex, TsukaimaType.body.fontSize * DisplayScale, colors.ink1)
    if (bitmap == null) {
        CodeBlock(code = tex, lang = "tex", modifier = modifier)
        return
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val column = maxWidth
        Box(Modifier.horizontalScroll(rememberScrollState())) {
            Box(Modifier.widthIn(min = column).padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                Image(bitmap = bitmap, contentDescription = tex)
            }
        }
    }
}
