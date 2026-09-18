package com.korgnote.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle

/**
 * Colors used by both reading mode ([OrgText]) and editing mode ([OrgSyntaxHighlightTransformation])
 * so that the two views stay visually consistent.
 */
object OrgColors {
    val todo = Color(0xFF2E7D32)
    val done = Color(0xFF9E9E9E)
    val planning = Color(0xFF616161)
    val keyword = Color(0xFF9E9E9E)
    val checkbox = Color(0xFF1565C0)
}

private val headingRegex = Regex("^(\\*+)\\s")
private val planningRegex = Regex("^\\s*(SCHEDULED|DEADLINE):")
private val checkboxRegex = Regex("^\\s*[-+]\\s+(\\[[ xX]])")

/**
 * Builds a highlighted version of a raw Org-mode text. The output has exactly the same characters
 * as [text] (only styles are added), so it can be used inside a [VisualTransformation] with an
 * identity [OffsetMapping].
 */
fun buildOrgHighlightedString(text: String, headingColor: Color): AnnotatedString {
    return buildAnnotatedString {
        var lineStart = 0
        while (lineStart <= text.length) {
            val newline = text.indexOf('\n', lineStart)
            val lineEnd = if (newline == -1) text.length else newline
            val line = text.substring(lineStart, lineEnd)
            appendHighlightedLine(line, headingColor)
            if (newline == -1) break
            append('\n')
            lineStart = newline + 1
        }
    }
}

private fun AnnotatedString.Builder.appendHighlightedLine(
    line: String,
    headingColor: Color
) {
    when {
        headingRegex.containsMatchIn(line) -> {
            withStyle(SpanStyle(color = headingColor, fontWeight = FontWeight.Bold)) {
                appendWithTodoTokens(line)
            }
        }

        planningRegex.containsMatchIn(line) -> {
            withStyle(SpanStyle(color = OrgColors.planning)) { append(line) }
        }

        line.startsWith("#+") -> {
            withStyle(SpanStyle(color = OrgColors.keyword)) { append(line) }
        }

        else -> {
            val cb = checkboxRegex.find(line)
            if (cb != null) {
                // The checkbox group is the last part of the match, so its position can be
                // derived from MatchResult.range (common stdlib) without MatchGroup.range.
                val box = cb.groupValues[1]
                val boxStart = cb.range.last + 1 - box.length
                append(line.substring(0, boxStart))
                withStyle(SpanStyle(color = OrgColors.checkbox, fontWeight = FontWeight.Bold)) {
                    append(box)
                }
                appendWithTodoTokens(line.substring(boxStart + box.length))
            } else {
                appendWithTodoTokens(line)
            }
        }
    }
}

private fun AnnotatedString.Builder.appendWithTodoTokens(text: String) {
    val tokens = listOf("TODO" to OrgColors.todo, "DONE" to OrgColors.done)
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

/**
 * [VisualTransformation] that applies Org-mode syntax colors to the text of a text field while
 * keeping the underlying text untouched (identity offset mapping).
 */
class OrgSyntaxHighlightTransformation(private val headingColor: Color) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        return TransformedText(
            buildOrgHighlightedString(text.text, headingColor),
            OffsetMapping.Identity
        )
    }
}
