package com.korgnote.app

import androidx.compose.foundation.layout.Column
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

@Composable
fun OrgText(content: String, modifier: Modifier = Modifier) {
    val lines = content.split('\n')
    Column(modifier = modifier, horizontalAlignment = Alignment.Start) {
        lines.forEach { raw ->
            val (isHeading, level, text) = parseHeading(raw)
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
