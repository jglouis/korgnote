package com.korgnote.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.korgnote.common.org.search.SearchHit

/**
 * Search across every note in the replica.
 *
 * The list is a `LazyColumn`, so a broad query costs what is on screen rather than what it matched;
 * the screen that hosts it therefore must not be inside a vertically scrolling parent — the same
 * constraint reading mode has, and for the same reason.
 */
@Composable
fun SearchScreen(
    query: String,
    results: List<SearchHit>,
    onQueryChange: (String) -> Unit,
    onOpen: (SearchHit) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Search notes") },
            placeholder = { Text("words, or tag:work") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))

        when {
            query.isBlank() -> Hint(
                "Type to search every note. Words match anywhere — body text, headings and file " +
                    "names. `tag:work` keeps only headings carrying that tag, and tags are " +
                    "inherited, so a tag on a parent heading counts for everything under it. " +
                    "Quote a phrase to search for it whole."
            )

            results.isEmpty() -> Hint("Nothing matched \"$query\".")

            else -> {
                Text(
                    text = if (results.size == 1) "1 result" else "${results.size} results",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(4.dp))
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(results) { hit ->
                        Result(hit = hit, onOpen = { onOpen(hit) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
private fun Result(hit: SearchHit, onOpen: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // Where the hit is, before what it says: the breadcrumb is what makes a line of body text
        // meaningful out of its document.
        Text(
            text = (listOf(hit.location()) + hit.breadcrumb).joinToString("  ›  "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        if (hit.kind != SearchHit.Kind.PATH) {
            val highlight = MaterialTheme.colorScheme.primary
            val snippet = remember(hit, highlight) { hit.snippet(highlight) }
            Text(
                text = snippet,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                maxLines = 2,
            )
        }
        if (hit.tags.isNotEmpty()) {
            Text(
                text = hit.tags.joinToString(":", prefix = ":", postfix = ":"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** `file.org:12`, or just the file name when the hit is the name itself. */
private fun SearchHit.location(): String =
    if (kind == SearchHit.Kind.PATH) path else "$path:$line"

/**
 * The matching line with the match emboldened, trimmed of leading indentation so that a deeply
 * nested list item still shows its text rather than its whitespace.
 */
private fun SearchHit.snippet(highlight: Color) = buildAnnotatedString {
    val trimmed = lineText.length - lineText.trimStart().length
    val text = lineText.drop(trimmed)
    if (match.isEmpty()) {
        append(text)
        return@buildAnnotatedString
    }
    val start = (match.first - trimmed).coerceIn(0, text.length)
    val end = (match.last + 1 - trimmed).coerceIn(start, text.length)
    append(text.substring(0, start))
    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = highlight)) {
        append(text.substring(start, end))
    }
    append(text.substring(end))
}
