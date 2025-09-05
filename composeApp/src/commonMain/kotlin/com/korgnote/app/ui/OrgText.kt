package com.korgnote.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.korgnote.common.org.OrgCheckboxItem
import com.korgnote.common.org.OrgDocument
import com.korgnote.common.org.OrgKeyword
import com.korgnote.common.org.OrgList
import com.korgnote.common.org.OrgNode
import com.korgnote.common.org.OrgRaw
import com.korgnote.common.org.OrgSection
import com.korgnote.common.org.OrgTable
import com.korgnote.common.org.OrgTableRow

private class Ord(var i: Int)

@Composable
fun OrgText(
    content: OrgNode,
    modifier: Modifier = Modifier,
    // Int parameter represents the ordinal index of the checkbox item in the original text
    onToggleCheckbox: ((ordinalIndex: Int, checked: Boolean) -> Unit)? = null,
) {
    // Fresh counter each composition to ensure consistent ordinal indexing on every recomposition
    val ord = Ord(0)
    Column(modifier = modifier, horizontalAlignment = Alignment.Start) {
        RenderNode(
            node = content,
            ordinal = ord,
            onToggleCheckbox = onToggleCheckbox,
        )
    }
}

@Composable
private fun RenderNode(
    node: OrgNode,
    ordinal: Ord,
    onToggleCheckbox: ((Int, Boolean) -> Unit)?
) {
    when (node) {
        is OrgDocument -> {
            Column(horizontalAlignment = Alignment.Start) {
                node.children.forEach { child -> RenderNode(child, ordinal, onToggleCheckbox) }
            }
        }

        is OrgSection -> {
            // Heading line
            val headingText = buildString {
                if (!node.todo.isNullOrEmpty()) {
                    append(node.todo)
                    append(' ')
                }
                append(node.title)
            }
            Text(
                text = buildOrgAnnotatedString(headingText),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            )
            // Section content (preformatted lines)
            if (node.content.isNotBlank()) {
                node.content.split('\n').forEach { ln ->
                    Text(text = buildOrgAnnotatedString(ln), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        is OrgList -> {
            Column(horizontalAlignment = Alignment.Start) {
                node.children.forEach { child ->
                    when (child) {
                        is OrgCheckboxItem -> {
                            val idx = ordinal.i
                            key(idx) {
                                CheckboxLine(checked = child.checked, text = child.text, onCheckedChange = { c ->
                                    onToggleCheckbox?.invoke(idx, c)
                                })
                            }
                            ordinal.i = idx + 1
                        }

                        is OrgRaw -> Text(
                            text = buildOrgAnnotatedString("${node.bulletType} ${child.content}"),
                            style = MaterialTheme.typography.bodyMedium
                        )

                        else -> RenderNode(child, ordinal, onToggleCheckbox)
                    }
                }
            }
        }

        is OrgCheckboxItem -> {
            val idx = ordinal.i
            key(idx) {
                CheckboxLine(checked = node.checked, text = node.text, onCheckedChange = { c ->
                    onToggleCheckbox?.invoke(idx, c)
                })
            }
            ordinal.i = idx + 1
        }

        is OrgKeyword -> {
            Text(text = buildOrgAnnotatedString(node.content), style = MaterialTheme.typography.bodySmall)
        }

        is OrgRaw -> {
            node.content.split('\n').forEach { ln ->
                Text(text = buildOrgAnnotatedString(ln), style = MaterialTheme.typography.bodyMedium)
            }
        }

        is OrgTable -> {
            // Very simple: print rows as lines like | cell |
            val row = node.rows
            if (row.cells.isNotEmpty()) {
                row.cells.forEach { cell ->
                    val raw = (cell as? OrgRaw)?.content ?: cell.toString()
                    Text(text = buildOrgAnnotatedString("| $raw |"), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        is OrgTableRow -> {
            val line = node.cells.joinToString(" | ") { (it as? OrgRaw)?.content ?: "" }
            Text(text = buildOrgAnnotatedString(line), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun CheckboxLine(
    checked: Boolean,
    text: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = { newValue: Boolean -> onCheckedChange(newValue) })
        Spacer(Modifier.width(6.dp))
        Text(text = buildOrgAnnotatedString(text), style = MaterialTheme.typography.bodyMedium)
    }
}

fun buildOrgAnnotatedString(text: String): AnnotatedString {
    // Highlight TODO (green) and DONE (grey) tokens as whole words
    val todoColor = Color(0xFF2E7D32)
    val doneColor = Color(0xFF9E9E9E)
    val tokens = listOf("TODO" to todoColor, "DONE" to doneColor)
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            var matched = false
            for ((token, color) in tokens) {
                if (text.regionMatches(i, token, 0, token.length)) {
                    val beforeOk = i == 0 || !text[i - 1].isLetterOrDigit()
                    val afterOk = i + token.length >= text.length || !text[i + token.length].isLetterOrDigit()
                    if (beforeOk && afterOk) {
                        withStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold)) { append(token) }
                        i += token.length
                        matched = true
                        break
                    }
                }
            }
            if (!matched) {
                append(text[i]); i++
            }
        }
    }
}
