package com.korgnote.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.korgnote.common.org.OrgSyntax
import com.korgnote.common.org.search.SearchHit
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.notes_remove_saved_search
import korgnote.composeapp.generated.resources.notes_save_search
import korgnote.composeapp.generated.resources.notes_saved_searches
import korgnote.composeapp.generated.resources.notes_search_hint
import korgnote.composeapp.generated.resources.notes_search_label
import korgnote.composeapp.generated.resources.notes_search_nothing
import korgnote.composeapp.generated.resources.notes_search_placeholder
import korgnote.composeapp.generated.resources.notes_search_results
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

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
    /** Whether [query] is one of the saved searches, which the field's bookmark shows. */
    saved: Boolean = false,
    /** Saves [query], or forgets it when [saved]; null hides the bookmark. */
    onToggleSaved: (() -> Unit)? = null,
    /** Opens the saved searches; null hides the button that does. */
    onShowSaved: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    // Opening the tab is opening it to type; coming back to results already there is not.
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (query.isBlank()) focus.requestFocus() }
    Column(modifier = modifier.fillMaxSize()) {
        // [actions] beside the field: there is no bar above it for them. The saved searches open
        // from the start of the row, where the note's bar opens its drawer.
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onShowSaved != null) {
                IconButton(onClick = onShowSaved) {
                    Icon(Icons.Filled.Bookmarks, contentDescription = stringResource(Res.string.notes_saved_searches))
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f).focusRequester(focus),
                label = { Text(stringResource(Res.string.notes_search_label)) },
                placeholder = { Text(stringResource(Res.string.notes_search_placeholder)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = if (onToggleSaved != null && query.isNotBlank()) {
                    {
                        IconButton(onClick = onToggleSaved) {
                            if (saved) {
                                Icon(Icons.Filled.Bookmark, contentDescription = stringResource(Res.string.notes_remove_saved_search))
                            } else {
                                Icon(Icons.Filled.BookmarkBorder, contentDescription = stringResource(Res.string.notes_save_search))
                            }
                        }
                    }
                } else {
                    null
                },
                singleLine = true,
            )
            actions()
        }
        Spacer(Modifier.height(8.dp))

        when {
            query.isBlank() -> Hint(stringResource(Res.string.notes_search_hint))

            results.isEmpty() -> Hint(stringResource(Res.string.notes_search_nothing, query))

            else -> {
                Text(
                    text = pluralStringResource(Res.plurals.notes_search_results, results.size, results.size),
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
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (hit.tags.isNotEmpty()) TagChips(hit.tags)
    }
}

/** `file.org:12`, or just the file name when the hit is the name itself. */
private fun SearchHit.location(): String =
    if (kind == SearchHit.Kind.PATH) path else "$path:$line"

/**
 * The matching line as reading mode shows it — a heading as its title, a link as its description,
 * emphasis as emphasis — with the match emboldened. A heading's tags are left to the chips below,
 * and links are left out: a tap on the result opens it.
 */
private fun SearchHit.snippet(highlight: Color): AnnotatedString {
    var line = lineText.trim()
    OrgSyntax.heading.find(line)?.let { stars ->
        line = line.substring(stars.value.length).trimStart()
        OrgSyntax.tags.find(line)?.let { line = line.substring(0, it.range.first) }
    }
    val shown = buildOrgInlineString(line, viewMode = true)
    return buildAnnotatedString {
        append(AnnotatedString(shown.text, shown.spanStyles))
        // Found again in what is shown, since the markup around it is gone: a match that spanned
        // some of that markup is simply not emboldened.
        if (!match.isEmpty()) {
            val words = lineText.substring(match.first, match.last + 1)
            val at = shown.text.indexOf(words, ignoreCase = true)
            if (at >= 0) addStyle(SpanStyle(fontWeight = FontWeight.Bold, color = highlight), at, at + words.length)
        }
    }
}
