package labs.suchattai.tsukaima.ui.theme

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import labs.suchattai.tsukaima.R

@OptIn(ExperimentalTextApi::class)
private fun variable(resId: Int, weight: Int, style: FontStyle = FontStyle.Normal) = Font(
    resId = resId,
    weight = FontWeight(weight),
    style = style,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val SourceSerif = FontFamily(
    variable(R.font.source_serif_4, 500),
    variable(R.font.source_serif_4, 600),
    variable(R.font.source_serif_4_italic, 500, FontStyle.Italic),
)

val InstrumentSans = FontFamily(
    variable(R.font.instrument_sans, 400),
    variable(R.font.instrument_sans, 500),
    variable(R.font.instrument_sans, 600),
)

val PlexMono = FontFamily(Font(R.font.ibm_plex_mono, FontWeight.Normal))

val NotoSerifJp = FontFamily(variable(R.font.noto_serif_jp_subset, 400))

private fun style(
    family: FontFamily,
    weight: Int,
    size: Double,
    lineHeight: Double? = null,
    fontStyle: FontStyle = FontStyle.Normal,
) = TextStyle(
    fontFamily = family,
    fontWeight = FontWeight(weight),
    fontStyle = fontStyle,
    fontSize = size.sp,
    lineHeight = lineHeight?.let { (size * it).sp } ?: TextStyle.Default.lineHeight,
)

object TsukaimaType {
    // Serif roles: wordmark, screen/section titles, agent status lines.
    val wordmark = style(SourceSerif, 600, 28.0, 1.1)
    val wordmarkLarge = style(SourceSerif, 600, 38.0, 1.1)
    val title = style(SourceSerif, 600, 22.0, 1.25)
    val titleSmall = style(SourceSerif, 600, 19.0, 1.25)
    val sectionTitle = style(SourceSerif, 600, 17.0, 1.25)
    val statusLine = style(SourceSerif, 500, 14.0, 1.3, FontStyle.Italic)

    // Sans roles: chat + UI.
    val body = style(InstrumentSans, 400, 15.5, 1.55)
    val ui = style(InstrumentSans, 500, 14.0, 1.4)

    /** Uppercase is applied by the component; a TextStyle cannot transform text. */
    val label = style(InstrumentSans, 600, 11.0, 1.2).copy(letterSpacing = 0.08.em)
    val caption = style(InstrumentSans, 400, 12.5, 1.4)

    val mono = style(PlexMono, 400, 13.0, 1.6)

    /** Noto Serif JP subset covers the glyphs of the familiar's name only. */
    val kanji = style(NotoSerifJp, 400, 14.0).copy(letterSpacing = 0.2.em)
}
