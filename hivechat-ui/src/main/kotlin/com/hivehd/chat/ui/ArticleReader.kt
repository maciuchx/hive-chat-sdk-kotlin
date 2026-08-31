package com.hivehd.chat.ui

import android.text.Html
import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.hivehd.chat.models.KnowledgeBaseArticle

/**
 * Reads a help-centre article without leaving the conversation.
 *
 * The website widget opens articles inline, and an article card that did
 * nothing when tapped was the one place the Android chat fell short of it.
 *
 * The body is server-rendered HTML. It goes into a `TextView` via
 * `Html.fromHtml` rather than a `WebView`: a WebView for a paragraph of
 * formatted text costs a process, a cookie jar and a JavaScript engine, and
 * would inherit none of the app's typography. `Html.fromHtml` handles the
 * bold/italic/links/lists a knowledge-base article actually contains.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ArticleReader(article: KnowledgeBaseArticle, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val bodyColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = article.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            val html = article.bodyHtml
            if (!html.isNullOrBlank()) {
                val spanned = remember(html) { Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT) }
                AndroidView(
                    factory = { context ->
                        TextView(context).apply {
                            /* Links in an article are the whole point of one —
                               without a movement method they render blue and
                               do nothing. */
                            movementMethod = LinkMovementMethod.getInstance()
                            setTextColor(bodyColor)
                            setLinkTextColor(linkColor)
                            textSize = 15f
                            setLineSpacing(0f, 1.35f)
                        }
                    },
                    update = { it.text = spanned },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                /* Only list and search rows omit the body; a fetched article
                   always has one. Showing the excerpt beats an empty sheet. */
                Text(
                    text = article.excerpt ?: "This article has no content yet.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
