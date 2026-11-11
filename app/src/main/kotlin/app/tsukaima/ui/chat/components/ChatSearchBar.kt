package app.tsukaima.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.tsukaima.domain.chat.ChatSearch
import app.tsukaima.ui.components.IconButton44
import app.tsukaima.ui.components.IconName
import app.tsukaima.ui.components.TsukaimaIcon
import app.tsukaima.ui.theme.Dimens
import app.tsukaima.ui.theme.TsukaimaColors
import app.tsukaima.ui.theme.TsukaimaShapes
import app.tsukaima.ui.theme.TsukaimaType
import app.tsukaima.ui.theme.tsukaima

/**
 * What the transcript is being searched for, as a row sees it: the [query], and whether this row is the match the
 * search stands on ([current]: its occurrences are marked strongly, the others' faintly).
 */
data class SearchMark(val query: String, val current: Boolean)

/** Set around each transcript row while the chat's search is open; null otherwise. */
val LocalChatSearch = compositionLocalOf<SearchMark?> { null }

/** [text] with every occurrence of the search query marked, when a search is open; else [text] as it is. */
@Composable
fun searchMarked(text: AnnotatedString): AnnotatedString {
    val mark = LocalChatSearch.current ?: return text
    val colors = MaterialTheme.tsukaima
    return remember(text, mark, colors) { marked(text, mark, colors) }
}

@Composable
fun searchMarked(text: String): AnnotatedString {
    val mark = LocalChatSearch.current
    val colors = MaterialTheme.tsukaima
    return remember(text, mark, colors) { if (mark == null) AnnotatedString(text) else marked(AnnotatedString(text), mark, colors) }
}

internal fun marked(text: AnnotatedString, mark: SearchMark, colors: TsukaimaColors): AnnotatedString {
    val ranges = ChatSearch.ranges(text.text, mark.query)
    if (ranges.isEmpty()) return text
    // The match the search stands on: the warning colour with the page's ink on it; any other match: a wash of it.
    val style = if (mark.current) SpanStyle(background = colors.warn, color = colors.surfacePage) else SpanStyle(background = colors.warn.copy(alpha = 0.28f))
    return AnnotatedString.Builder(text).apply { ranges.forEach { addStyle(style, it.first, it.last + 1) } }.toAnnotatedString()
}

/**
 * The chat's top bar while searching: the query field, "2 of 5" (the messages that hold it), arrows to the
 * previous and next match, and Close.
 */
@Composable
fun ChatSearchBar(
    query: String,
    onQuery: (String) -> Unit,
    position: Int,
    total: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.tsukaima
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val count = when {
        query.trim().length < ChatSearch.MIN_QUERY -> ""
        total == 0 -> "No match"
        else -> "$position of $total"
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surfacePage)
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .defaultMinSize(minHeight = Dimens.hit)
                .background(colors.surfaceRaised, TsukaimaShapes.pill)
                .border(1.dp, colors.hairline, TsukaimaShapes.pill)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TsukaimaIcon(IconName.Search, contentDescription = null, size = 18.dp, tint = colors.ink3)
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = TsukaimaType.body.copy(color = colors.ink1),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onNext() }),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focus)
                    .semantics { contentDescription = "Search this chat" },
                decorationBox = { field ->
                    if (query.isEmpty()) Text("Search this chat", style = TsukaimaType.body, color = colors.ink3)
                    field()
                },
            )
            if (count.isNotEmpty()) Text(count, style = TsukaimaType.caption, color = colors.ink2, maxLines = 1)
        }
        IconButton44(IconName.ExpandMore, "Next match", onClick = onNext, enabled = total > 0)
        IconButton44(IconName.ArrowUpward, "Previous match", onClick = onPrevious, enabled = total > 0)
        IconButton44(IconName.Close, "Close search", onClick = onClose)
    }
}
