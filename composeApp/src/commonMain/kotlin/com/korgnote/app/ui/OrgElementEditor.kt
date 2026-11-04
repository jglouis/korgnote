package com.korgnote.app.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.korgnote.common.org.OrgTodoConfig
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.editor_editing_element
import org.jetbrains.compose.resources.stringResource

/**
 * Editor for a single element — the only editing surface there is.
 *
 * An element holds a heading and its own body, so highlighting is never scoped to a viewport: the
 * per-line cache already makes a keystroke cost one line, and the text is small enough that laying
 * it out is cheap.
 */
@Composable
internal fun OrgElementEditor(
    elementKey: Int,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onCompletionKey: (CompletionKey) -> Boolean,
    todoKeywords: OrgTodoConfig,
    /** What the field says it is for; that it edits an element when null. */
    label: String? = null,
) {
    val headingColor = MaterialTheme.colorScheme.primary
    val highlighting = remember(headingColor) { OrgSyntaxHighlightTransformation(headingColor) }
    highlighting.fallbackKeywords = todoKeywords
    val interactionSource = remember { MutableInteractionSource() }
    val textStyle = LocalTextStyle.current.merge(
        TextStyle(fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
    )

    val focusRequester = rememberAutoFocus(elementKey)

    // A fully controlled field: the text *and the caret* come from above. The caret has to live
    // there because the suggestions computed from it are shown on a strip pinned to the screen,
    // which is nowhere near this composable — and because accepting one moves the caret.
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).focusRequester(focusRequester)
            .completionKeys(onCompletionKey),
        textStyle = textStyle,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        interactionSource = interactionSource,
        visualTransformation = highlighting,
        decorationBox = { innerTextField ->
            OutlinedTextFieldDefaults.DecorationBox(
                value = value.text,
                innerTextField = innerTextField,
                enabled = true,
                singleLine = false,
                // None on purpose: the decoration box would otherwise run the highlighter a second
                // time on every keystroke, just to decide whether to show a placeholder.
                visualTransformation = VisualTransformation.None,
                interactionSource = interactionSource,
                label = { Text(label ?: stringResource(Res.string.editor_editing_element)) },
            )
        },
    )
}
