package com.korgnote.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

@Composable
fun OrgText(
    content: String,
    modifier: Modifier = Modifier,
    onToggleCheckbox: ((lineIndex: Int, checked: Boolean) -> Unit)? = null,
) {
    val lines = content.split('\n')
    Column(modifier = modifier, horizontalAlignment = Alignment.Start) {
        lines.forEachIndexed { index, raw ->
            val (isHeading, level, text) = parseHeading(raw)
            // Detect checkbox list item: - [ ] or - [X]/[x]
            val cbMatch = Regex("^\\s*- \\[([ xX])]\\s*(.*)").find(text)
            if (cbMatch != null) {
                val mark = cbMatch.groupValues[1]
                val itemText = cbMatch.groupValues[2]
                val checked = mark.equals("x", ignoreCase = true)
                CheckboxLine(
                    checked = checked,
                    text = itemText,
                    onCheckedChange = { newValue -> onToggleCheckbox?.invoke(index, newValue) }
                )
            } else {
                val annotated = buildOrgAnnotatedString(text)
                val baseStyle = when {
                    isHeading && level == 1 -> MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    isHeading && level == 2 -> MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    isHeading && level >= 3 -> MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                    else -> MaterialTheme.typography.bodyMedium
                }
                Text(text = annotated, style = baseStyle)
            }
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

private fun parseHeading(line: String): Triple<Boolean, Int, String> {
    val trim = line.trimEnd()
    val regex = Regex("^(\\*+)\\s+(.*)")
    val match = regex.find(trim)
    return if (match != null) {
        val stars = match.groupValues[1]
        val text = match.groupValues[2]
        Triple(true, stars.length, text)
    } else Triple(false, 0, trim)
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
            if (!matched) { append(text[i]); i++ }
        }
    }
}
