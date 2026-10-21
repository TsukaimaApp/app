package app.tsukaima.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.tsukaima.ui.theme.TsukaimaTheme
import app.tsukaima.ui.theme.tsukaima

/** Shared page-coloured wrapper for the light/dark previews in this package. */
@Composable
internal fun PreviewPage(dark: Boolean, content: @Composable () -> Unit) {
    TsukaimaTheme(darkTheme = dark) {
        Column(Modifier.background(MaterialTheme.tsukaima.surfacePage)) { content() }
    }
}
