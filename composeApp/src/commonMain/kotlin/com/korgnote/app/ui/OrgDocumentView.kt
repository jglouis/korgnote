package com.korgnote.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.korgnote.common.org.orgElements
import com.korgnote.common.org.sliceOf
import com.korgnote.common.org.toOrgDocument

/**
 * Reading view of a whole document, rendered one editable element at a time.
 *
 * Each element is parsed and rendered from its own slice of [text] rather than from a single parse
 * of the document. That costs a little more parsing and buys the property this screen depends on:
 * every rendered element knows the exact character range it came from, so editing one is a text
 * replacement over that range and can never disturb its neighbours.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OrgDocumentView(
    text: String,
    modifier: Modifier = Modifier,
    // The offset of the tapped checkbox in [text]; the view model finds the node there.
    onToggleCheckbox: ((offsetInDocument: Int) -> Unit)? = null,
    editingElementIndex: Int? = null,
    elementDraft: String = "",
    elementDraftCaret: Int = 0,
    onElementDraftChange: (String) -> Unit = {},
    onEditElement: ((elementIndex: Int) -> Unit)? = null,
    onAddElementAfter: ((elementIndex: Int) -> Unit)? = null,
    scrollToElementIndex: Int? = null,
    onScrolledToElement: () -> Unit = {},
) {
    val elements = remember(text) { text.orgElements() }
    val listState = rememberLazyListState()

    LazyColumn(
        modifier = modifier,
        state = listState,
        horizontalAlignment = Alignment.Start,
    ) {
        // Every element gets an item, including a blank preamble that renders nothing, so that an
        // item's index is its element's index and a scroll target needs no translation.
        itemsIndexed(elements, key = { index, _ -> index }) { index, element ->
            val slice = remember(text, element) { element.sliceOf(text) }
            if (element.isPreamble && slice.isBlank()) return@itemsIndexed

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Start,
            ) {
                if (index == editingElementIndex) {
                    OrgElementEditor(
                        elementKey = index,
                        value = elementDraft,
                        initialCaret = elementDraftCaret,
                        onValueChange = onElementDraftChange,
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                // Plain taps are left alone: checkboxes and links already use them.
                                onClick = {},
                                onLongClick = { onEditElement?.invoke(index) },
                            ),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            val document = remember(slice) { slice.toOrgDocument() }
                            OrgText(
                                content = document,
                                // The element was parsed from its own slice, so its nodes carry
                                // slice-relative ranges; shifting by the element's start makes them
                                // offsets into the whole document.
                                onToggleCheckbox = onToggleCheckbox?.let { toggle ->
                                    { item -> toggle(element.start + item.range.first) }
                                },
                            )
                        }
                        Column {
                            if (onEditElement != null) {
                                IconButton(
                                    onClick = { onEditElement(index) },
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.Edit,
                                        contentDescription = "Edit this element",
                                        tint = MaterialTheme.colorScheme.outline,
                                    )
                                }
                            }
                            if (onAddElementAfter != null) {
                                IconButton(
                                    onClick = { onAddElementAfter(index) },
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.Add,
                                        contentDescription = "Add a heading after this element",
                                        tint = MaterialTheme.colorScheme.outline,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(scrollToElementIndex, elements) {
        val index = scrollToElementIndex
        if (index != null && index in elements.indices) {
            // scrollToItem, not bringIntoView: the target is usually far off screen and has not been
            // composed yet, so there is no node to bring anywhere — the list has to go to it.
            listState.scrollToItem(index)
            onScrolledToElement()
        }
    }
}

/**
 * Editor for a single element.
 *
 * Deliberately simpler than the whole-file editor: an element holds a heading and its own body, so
 * there is no need to scope highlighting to the viewport — the per-line cache already makes a
 * keystroke cost one line, and the text is small enough that laying it out is cheap.
 */
@Composable
private fun OrgElementEditor(
    elementKey: Int,
    value: String,
    initialCaret: Int,
    onValueChange: (String) -> Unit,
) {
    val headingColor = MaterialTheme.colorScheme.primary
    val highlighting = remember(headingColor) { OrgSyntaxHighlightTransformation(headingColor) }
    val interactionSource = remember { MutableInteractionSource() }
    val textStyle = LocalTextStyle.current.merge(
        TextStyle(fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
    )

    // TextFieldValue rather than a plain String so the caret can start after the stars of a newly
    // added heading. It is the local source of truth for the session; the text is mirrored upward.
    var field by remember(elementKey) {
        mutableStateOf(TextFieldValue(value, TextRange(initialCaret.coerceIn(0, value.length))))
    }
    val focusRequester = remember(elementKey) { FocusRequester() }
    LaunchedEffect(elementKey) { focusRequester.requestFocus() }

    BasicTextField(
        value = field,
        onValueChange = {
            field = it
            onValueChange(it.text)
        },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).focusRequester(focusRequester),
        textStyle = textStyle,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        interactionSource = interactionSource,
        visualTransformation = highlighting,
        decorationBox = { innerTextField ->
            OutlinedTextFieldDefaults.DecorationBox(
                value = field.text,
                innerTextField = innerTextField,
                enabled = true,
                singleLine = false,
                // None on purpose: the decoration box would otherwise run the highlighter a second
                // time on every keystroke, just to decide whether to show a placeholder.
                visualTransformation = VisualTransformation.None,
                interactionSource = interactionSource,
                label = { Text("Editing element") },
            )
        },
    )
}
