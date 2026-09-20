package com.korgnote.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import com.korgnote.common.org.OrgDocument
import com.korgnote.common.org.OrgTodoConfig
import com.korgnote.common.org.isOrgTableLine
import com.korgnote.common.org.isOrgTableRuleLine
import com.korgnote.common.org.parseTodoConfigFrom
import io.ktor.http.encodeURLQueryComponent

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
    val stars = Color(0xFFB0BEC5)
    val tag = Color(0xFF7B1FA2)
    val priority = Color(0xFFC62828)
    val statistics = Color(0xFF757575)
    val link = Color(0xFF1565C0)
    val timestamp = Color(0xFF00796B)
    val code = Color(0xFFAD1457)
    val verbatim = Color(0xFF5D4037)
    val markup = Color(0xFFB0BEC5)
    val bullet = Color(0xFF6D4C41)
    val descriptionTerm = Color(0xFF37474F)
    val blockDelimiter = Color(0xFF9E9E9E)
    val blockBackground = Color(0x1A9E9E9E)
    val drawer = Color(0xFF9E9E9E)
    val comment = Color(0xFF9E9E9E)
    val table = Color(0xFF546E7A)
    val tableRule = Color(0xFFB0BEC5)
    val footnote = Color(0xFF6A1B9A)
    val horizontalRule = Color(0xFFB0BEC5)
}

/** TODO keywords used when a document does not declare any with `#+TODO:`. */
val DefaultOrgTodoConfig = OrgTodoConfig(setOf("TODO"), setOf("DONE"))

/** Adds the default TODO/DONE keywords to a configuration parsed from a document. */
fun OrgTodoConfig.withDefaults(): OrgTodoConfig =
    OrgTodoConfig(todo + DefaultOrgTodoConfig.todo, done + DefaultOrgTodoConfig.done)

/** Extracts the TODO keyword configuration (`#+TODO:` lines) from raw Org text, including defaults. */
fun orgTodoConfigOf(text: String): OrgTodoConfig =
    parseTodoConfigFrom(text.lineSequence().filter { it.startsWith("#+TODO", ignoreCase = true) }.toList()).withDefaults()

/** Extracts the TODO keyword configuration from a parsed document, including defaults. */
fun orgTodoConfigOf(doc: OrgDocument): OrgTodoConfig = parseTodoConfigFrom(doc.metadata).withDefaults()

/** Color of a TODO keyword, or null if [word] is not a known keyword. */
fun OrgTodoConfig.colorFor(word: String): Color? = when (word) {
    in done -> OrgColors.done
    in todo -> OrgColors.todo
    else -> null
}

/**
 * Line-level Org syntax patterns shared by the highlighter and the reading-mode renderer.
 *
 * The `\]` escapes are flagged as redundant by the IDE (JVM semantics) but they are required:
 * Kotlin/JS and Kotlin/Wasm compile regexes with the JavaScript `u` flag, where a lone `]` is a SyntaxError.
 */
@Suppress("RegExpRedundantEscape")
object OrgSyntax {
    val heading = Regex("^(\\*+)\\s")
    val blockBegin = Regex("^\\s*#\\+BEGIN_(\\S+)(.*)$", RegexOption.IGNORE_CASE)
    val blockEnd = Regex("^\\s*#\\+END_(\\S*)\\s*$", RegexOption.IGNORE_CASE)
    val keyword = Regex("^\\s*#\\+")
    val comment = Regex("^\\s*#(\\s|$)")
    val drawerBegin = Regex("^\\s*:[A-Za-z0-9_-]+:\\s*$")
    val drawerEnd = Regex("^\\s*:END:\\s*$", RegexOption.IGNORE_CASE)
    val property = Regex("^(\\s*:[A-Za-z0-9_+-]+:)(.*)$")
    val planning = Regex("^\\s*(SCHEDULED|DEADLINE|CLOSED):")
    val planningKeyword = Regex("(SCHEDULED|DEADLINE|CLOSED):")
    val horizontalRule = Regex("^\\s*-{5,}\\s*$")
    val listItem = Regex("^(\\s*)([-+*]|\\d+[.)])(\\s+)(?:(\\[[ xX-]\\])(?=\\s|$))?")
    val descriptionTerm = Regex("^(\\s*)(.+?)(\\s+::)(\\s|$)")
    val priority = Regex("^\\[#[A-Z0-9]\\]")
    val tags = Regex("\\s+(:[\\p{L}\\p{N}_@#%]+(?::[\\p{L}\\p{N}_@#%]+)*:)\\s*$")

    /** Block types whose content is rendered verbatim in a monospace font. */
    val monospaceBlocks = setOf("src", "example", "export")

    fun isTableLine(line: String) = line.isOrgTableLine()
    fun isTableRuleLine(line: String) = line.isOrgTableRuleLine()
}

/** True when a `:PROPERTIES:` drawer property key (as captured by [OrgSyntax.property]) is `LOCATION`. */
private fun isLocationPropertyKey(key: String) = key.trim().trim(':').equals("LOCATION", ignoreCase = true)

/** Google Maps search URL for a `LOCATION` property value, opened via the platform's [androidx.compose.ui.platform.UriHandler]. */
fun googleMapsUrl(location: String): String =
    "https://www.google.com/maps/search/?api=1&query=" + location.encodeURLQueryComponent(encodeFull = true, spaceToPlus = true)

// `\]` must stay escaped: see the note on [OrgSyntax] (JavaScript `u`-mode regexes).
@Suppress("RegExpRedundantEscape")
private val linkRegex = Regex("\\[\\[([^\\[\\]]+)\\](?:\\[([^\\[\\]]*)\\])?\\]")
@Suppress("RegExpRedundantEscape")
private val urlRegex = Regex("(?:https?|ftp|mailto):[^\\s<>\\[\\]()\"']+")
@Suppress("RegExpRedundantEscape")
private val timestampRegex = Regex("[<\\[]\\d{4}-\\d{2}-\\d{2}(?: [^\\]>\\n]*)?[>\\]]")
@Suppress("RegExpRedundantEscape")
private val footnoteRegex = Regex("\\[fn:[^\\]]+\\]")
@Suppress("RegExpRedundantEscape")
private val statisticsRegex = Regex("\\[(?:\\d+/\\d+|\\d+%)\\]")

private class EmphasisSpec(val marker: Char, val regex: Regex, val style: SpanStyle, val markupColor: Color)

private val emphasisSpecs: Map<Char, EmphasisSpec> = listOf(
    '*' to SpanStyle(fontWeight = FontWeight.Bold),
    '/' to SpanStyle(fontStyle = FontStyle.Italic),
    '_' to SpanStyle(textDecoration = TextDecoration.Underline),
    '+' to SpanStyle(textDecoration = TextDecoration.LineThrough),
    '=' to SpanStyle(color = OrgColors.verbatim, fontFamily = FontFamily.Monospace),
    '~' to SpanStyle(color = OrgColors.code, fontFamily = FontFamily.Monospace),
).associate { (m, style) ->
    val lit = if (m == '*' || m == '+') "\\$m" else "$m"
    // marker, non-space, (anything not the marker, lazily, ending with non-space)?, marker
    val regex = Regex("$lit([^\\s$m](?:[^$m\\n]*?[^\\s$m])?)$lit")
    m to EmphasisSpec(m, regex, style, OrgColors.markup)
}

private const val EMPHASIS_PRE = " \t-('\"{"
private const val EMPHASIS_POST = " \t-.,:!?;'\")}["

private val linkStyle = SpanStyle(color = OrgColors.link, textDecoration = TextDecoration.Underline)

/**
 * Highlights the inline (intra-line) Org syntax of [text]: links, emphasis, timestamps, footnotes
 * and statistics cookies. With [viewMode] the markup characters (emphasis markers, link brackets and
 * link targets) are dropped and URL links become clickable; otherwise every character of [text] is
 * preserved so the result can be used in a [VisualTransformation].
 */
fun buildOrgInlineString(text: String, viewMode: Boolean = true): AnnotatedString =
    buildAnnotatedString { appendInline(text, viewMode) }

/** Appends [text] with inline Org syntax highlighted (see [buildOrgInlineString]). */
fun AnnotatedString.Builder.appendOrgInline(text: String, viewMode: Boolean = true) = appendInline(text, viewMode)

/**
 * Builds a highlighted version of a raw multi-line Org text. When [viewMode] is false the output has
 * exactly the same characters as [text] (only styles are added), so it can be used inside a
 * [VisualTransformation] with an identity [OffsetMapping].
 */
fun buildOrgHighlightedString(
    text: String,
    headingColor: Color,
    todoConfig: OrgTodoConfig = orgTodoConfigOf(text),
    viewMode: Boolean = false,
): AnnotatedString {
    return buildAnnotatedString {
        val state = LineState()
        var lineStart = 0
        while (lineStart <= text.length) {
            val newline = text.indexOf('\n', lineStart)
            val lineEnd = if (newline == -1) text.length else newline
            val line = text.substring(lineStart, lineEnd)
            appendHighlightedLine(line, headingColor, todoConfig, viewMode, state)
            if (newline == -1) break
            append('\n')
            lineStart = newline + 1
        }
    }
}

private class LineState {
    var blockType: String? = null
    var inDrawer: Boolean = false
}

/**
 * Heading sizes, shared by the fully styled path and the metrics-only path used off screen: a
 * heading must occupy the same height whether or not it is currently visible, or the document's
 * total height would change as the styled window follows the viewport.
 */
private fun headingFontSize(level: Int): TextUnit = when (level) {
    1 -> 1.3.em
    2 -> 1.15.em
    3 -> 1.05.em
    else -> TextUnit.Unspecified
}

/**
 * Appends a line that lies outside the styled window: its characters, plus only the styling that
 * affects layout metrics (heading size and weight). Colour is skipped — it costs spans without
 * changing where anything sits.
 *
 * Mirrors [appendHighlightedLine]'s branch order so [state] advances identically, which is what
 * lets a visible line inside a block or drawer still be styled correctly.
 *
 * Everything else is metric-neutral because the editor renders in a monospace font, where bold and
 * regular glyphs share the same advance width.
 */
private fun AnnotatedString.Builder.appendMetricsOnlyLine(line: String, state: LineState) {
    if (state.blockType != null) {
        if (OrgSyntax.blockEnd.containsMatchIn(line)) state.blockType = null
        append(line)
        return
    }
    if (state.inDrawer) {
        if (OrgSyntax.drawerEnd.containsMatchIn(line)) state.inDrawer = false
        append(line)
        return
    }
    when {
        OrgSyntax.heading.containsMatchIn(line) -> {
            val stars = OrgSyntax.heading.find(line)!!.groupValues[1]
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = headingFontSize(stars.length))) {
                append(line)
            }
        }

        OrgSyntax.blockBegin.containsMatchIn(line) -> {
            state.blockType = OrgSyntax.blockBegin.find(line)!!.groupValues[1].lowercase()
            append(line)
        }

        OrgSyntax.drawerBegin.containsMatchIn(line) -> {
            state.inDrawer = !OrgSyntax.drawerEnd.containsMatchIn(line)
            append(line)
        }

        else -> append(line)
    }
}

private data class LineStateSnapshot(val blockType: String?, val inDrawer: Boolean)

private fun LineState.snapshot() = LineStateSnapshot(blockType, inDrawer)

private fun LineState.restore(snapshot: LineStateSnapshot) {
    blockType = snapshot.blockType
    inDrawer = snapshot.inDrawer
}

/**
 * Highlights a document by caching each line's styled result, keyed on the line's text and the
 * block/drawer state the line starts in. A keystroke changes one line, so every other line is
 * reused instead of re-running the grammar over the whole document.
 *
 * Output is identical to [buildOrgHighlightedString] for the same input; the cache only decides how
 * much work is repeated.
 */
private class OrgHighlightCache(private val headingColor: Color) {
    private var sourceText: String? = null
    private var sourceResult: AnnotatedString? = null
    private var todoConfig: OrgTodoConfig? = null
    private var lines: List<String> = emptyList()
    private var lineResults: List<AnnotatedString> = emptyList()
    private var statesBefore: List<LineStateSnapshot> = emptyList()
    private var statesAfter: List<LineStateSnapshot> = emptyList()
    private var lineStyled: List<Boolean> = emptyList()
    private var cachedVisible: IntRange? = null
    private var hasCachedVisible = false

    /**
     * [visible] limits full styling to the characters in that range; lines outside it get
     * [appendMetricsOnlyLine]. Passing null styles the whole document.
     */
    fun highlight(text: String, visible: IntRange?): AnnotatedString {
        if (visible == cachedVisible) {
            sourceResult?.let { if (sourceText == text) return it }
        }

        val newLines = text.split('\n')
        val config = orgTodoConfigOf(text)
        // A changed #+TODO: configuration restyles keywords anywhere in the document, so no cached
        // line survives it. A moved window does not invalidate anything by itself: each line is
        // cached along with whether it was styled, so only the lines crossing the window's edge are
        // recomputed.
        val oldLines = if (config == todoConfig) lines else emptyList()

        val prefix = commonPrefixLength(oldLines, newLines)
        val suffix = commonSuffixLength(oldLines, newLines, prefix)

        val results = ArrayList<AnnotatedString>(newLines.size)
        val before = ArrayList<LineStateSnapshot>(newLines.size)
        val after = ArrayList<LineStateSnapshot>(newLines.size)
        val styledFlags = ArrayList<Boolean>(newLines.size)
        val state = LineState()
        var lineStart = 0

        for (i in newLines.indices) {
            val incoming = state.snapshot()
            before.add(incoming)
            val lineEnd = lineStart + newLines[i].length
            val styled = visible == null || (lineStart <= visible.last && lineEnd >= visible.first)
            lineStart = lineEnd + 1
            val oldIndex = when {
                i < prefix -> i
                i >= newLines.size - suffix -> oldLines.size - (newLines.size - i)
                else -> -1
            }
            // A line's styling depends on the state it starts in and on whether it was inside
            // the window, so a matching text is only reusable when both of those match too.
            val reusable = oldIndex >= 0 && oldIndex < lineResults.size &&
                statesBefore[oldIndex] == incoming && lineStyled[oldIndex] == styled
            if (reusable) {
                results.add(lineResults[oldIndex])
                state.restore(statesAfter[oldIndex])
            } else if (styled) {
                results.add(
                    buildAnnotatedString {
                        appendHighlightedLine(newLines[i], headingColor, config, viewMode = false, state)
                    }
                )
            } else {
                results.add(buildAnnotatedString { appendMetricsOnlyLine(newLines[i], state) })
            }
            after.add(state.snapshot())
            styledFlags.add(styled)
        }

        val assembled = buildAnnotatedString {
            for (i in results.indices) {
                if (i > 0) append('\n')
                append(results[i])
            }
        }

        sourceText = text
        sourceResult = assembled
        todoConfig = config
        lines = newLines
        lineResults = results
        statesBefore = before
        statesAfter = after
        lineStyled = styledFlags
        cachedVisible = visible
        hasCachedVisible = true
        return assembled
    }
}

private fun commonPrefixLength(old: List<String>, new: List<String>): Int {
    val max = minOf(old.size, new.size)
    var i = 0
    while (i < max && old[i] == new[i]) i++
    return i
}

private fun commonSuffixLength(old: List<String>, new: List<String>, prefix: Int): Int {
    val max = minOf(old.size, new.size) - prefix
    var i = 0
    while (i < max && old[old.size - 1 - i] == new[new.size - 1 - i]) i++
    return i
}

private fun AnnotatedString.Builder.appendHighlightedLine(
    line: String,
    headingColor: Color,
    todoConfig: OrgTodoConfig,
    viewMode: Boolean,
    state: LineState,
) {
    val blockType = state.blockType
    if (blockType != null) {
        if (OrgSyntax.blockEnd.containsMatchIn(line)) {
            state.blockType = null
            withStyle(SpanStyle(color = OrgColors.blockDelimiter)) { append(line) }
        } else {
            appendBlockBody(line, blockType)
        }
        return
    }
    if (state.inDrawer) {
        if (OrgSyntax.drawerEnd.containsMatchIn(line)) state.inDrawer = false
        appendDrawerLine(line, viewMode)
        return
    }
    when {
        OrgSyntax.heading.containsMatchIn(line) -> appendHeading(line, headingColor, todoConfig, viewMode)

        OrgSyntax.blockBegin.containsMatchIn(line) -> {
            state.blockType = OrgSyntax.blockBegin.find(line)!!.groupValues[1].lowercase()
            withStyle(SpanStyle(color = OrgColors.blockDelimiter)) { append(line) }
        }

        OrgSyntax.blockEnd.containsMatchIn(line) -> {
            withStyle(SpanStyle(color = OrgColors.blockDelimiter)) { append(line) }
        }

        OrgSyntax.keyword.containsMatchIn(line) -> {
            withStyle(SpanStyle(color = OrgColors.keyword)) { append(line) }
        }

        OrgSyntax.comment.containsMatchIn(line) -> {
            withStyle(SpanStyle(color = OrgColors.comment, fontStyle = FontStyle.Italic)) { append(line) }
        }

        OrgSyntax.drawerBegin.containsMatchIn(line) -> {
            state.inDrawer = !OrgSyntax.drawerEnd.containsMatchIn(line)
            appendDrawerLine(line, viewMode)
        }

        OrgSyntax.planning.containsMatchIn(line) -> appendPlanning(line, viewMode)

        OrgSyntax.isTableLine(line) -> appendTableLine(line, viewMode)

        OrgSyntax.horizontalRule.containsMatchIn(line) -> {
            withStyle(SpanStyle(color = OrgColors.horizontalRule)) { append(line) }
        }

        else -> {
            val item = OrgSyntax.listItem.find(line)
            if (item != null) appendListItem(line, item, viewMode) else appendInline(line, viewMode)
        }
    }
}

private fun AnnotatedString.Builder.appendBlockBody(line: String, blockType: String) {
    val style = when (blockType) {
        in OrgSyntax.monospaceBlocks -> SpanStyle(fontFamily = FontFamily.Monospace, background = OrgColors.blockBackground)
        "quote", "verse" -> SpanStyle(fontStyle = FontStyle.Italic)
        else -> SpanStyle(background = OrgColors.blockBackground)
    }
    withStyle(style) { append(line) }
}

private fun AnnotatedString.Builder.appendDrawerLine(line: String, viewMode: Boolean) {
    withStyle(SpanStyle(color = OrgColors.drawer)) {
        val prop = OrgSyntax.property.find(line)
        if (prop != null) {
            val key = prop.groupValues[1]
            val value = prop.groupValues[2]
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(key) }
            if (viewMode && isLocationPropertyKey(key) && value.isNotBlank()) {
                withLink(LinkAnnotation.Url(googleMapsUrl(value.trim()), TextLinkStyles(style = linkStyle))) {
                    append(value)
                }
            } else {
                append(value)
            }
        } else {
            append(line)
        }
    }
}

private fun AnnotatedString.Builder.appendHeading(
    line: String,
    headingColor: Color,
    todoConfig: OrgTodoConfig,
    viewMode: Boolean,
) {
    val stars = OrgSyntax.heading.find(line)!!.groupValues[1]
    val size: TextUnit = headingFontSize(stars.length)
    withStyle(SpanStyle(color = headingColor, fontWeight = FontWeight.Bold, fontSize = size)) {
        withStyle(SpanStyle(color = OrgColors.stars)) { append(stars) }
        var rest = line.substring(stars.length)

        fun consumeWhitespace() {
            val ws = rest.takeWhile { it.isWhitespace() }
            append(ws)
            rest = rest.drop(ws.length)
        }
        consumeWhitespace()

        val firstWord = rest.takeWhile { !it.isWhitespace() }
        val todoColor = todoConfig.colorFor(firstWord)
        if (todoColor != null) {
            withStyle(SpanStyle(color = todoColor)) { append(firstWord) }
            rest = rest.drop(firstWord.length)
            consumeWhitespace()
        }

        val prio = OrgSyntax.priority.find(rest)
        if (prio != null) {
            withStyle(SpanStyle(color = OrgColors.priority)) { append(prio.value) }
            rest = rest.drop(prio.value.length)
            consumeWhitespace()
        }

        val tags = OrgSyntax.tags.find(rest)
        val body = if (tags != null) rest.substring(0, tags.range.first) else rest
        appendInline(body, viewMode)
        if (tags != null) {
            val tail = rest.substring(tags.range.first)
            val tagStart = tail.indexOf(tags.groupValues[1])
            append(tail.substring(0, tagStart))
            withStyle(SpanStyle(color = OrgColors.tag, fontWeight = FontWeight.Normal)) {
                append(tags.groupValues[1])
            }
            append(tail.substring(tagStart + tags.groupValues[1].length))
        }
    }
}

private fun AnnotatedString.Builder.appendPlanning(line: String, viewMode: Boolean) {
    withStyle(SpanStyle(color = OrgColors.planning)) {
        var pos = 0
        for (m in OrgSyntax.planningKeyword.findAll(line)) {
            appendInline(line.substring(pos, m.range.first), viewMode)
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(m.value) }
            pos = m.range.last + 1
        }
        appendInline(line.substring(pos), viewMode)
    }
}

private fun AnnotatedString.Builder.appendTableLine(line: String, viewMode: Boolean) {
    withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
        if (OrgSyntax.isTableRuleLine(line)) {
            withStyle(SpanStyle(color = OrgColors.tableRule)) { append(line) }
            return@withStyle
        }
        var cellStart = 0
        for (i in line.indices) {
            if (line[i] == '|') {
                appendInline(line.substring(cellStart, i), viewMode)
                withStyle(SpanStyle(color = OrgColors.table)) { append('|') }
                cellStart = i + 1
            }
        }
        appendInline(line.substring(cellStart), viewMode)
    }
}

private fun AnnotatedString.Builder.appendListItem(line: String, item: MatchResult, viewMode: Boolean) {
    append(item.groupValues[1])
    withStyle(SpanStyle(color = OrgColors.bullet, fontWeight = FontWeight.Bold)) { append(item.groupValues[2]) }
    append(item.groupValues[3])
    val box = item.groupValues[4]
    if (box.isNotEmpty()) {
        withStyle(SpanStyle(color = OrgColors.checkbox, fontWeight = FontWeight.Bold)) { append(box) }
    }
    // The checkbox (if any) is part of the match, so the remainder starts right after it.
    appendItemText(line.substring(item.range.last + 1), viewMode)
}

/** Text of a list item after its bullet/checkbox: an optional `term ::` followed by inline content. */
private fun AnnotatedString.Builder.appendItemText(text: String, viewMode: Boolean) {
    var rest = text
    val desc = OrgSyntax.descriptionTerm.find(rest)
    if (desc != null) {
        append(desc.groupValues[1])
        withStyle(SpanStyle(color = OrgColors.descriptionTerm, fontWeight = FontWeight.Bold)) {
            appendInline(desc.groupValues[2], viewMode)
        }
        withStyle(SpanStyle(color = OrgColors.markup)) { append(desc.groupValues[3]) }
        rest = rest.substring(desc.groupValues[1].length + desc.groupValues[2].length + desc.groupValues[3].length)
    }
    appendInline(rest, viewMode)
}

/** Highlights the text of a list item (without its bullet/checkbox) for reading mode. */
fun buildOrgItemString(text: String): AnnotatedString = buildAnnotatedString { appendItemText(text, viewMode = true) }

private fun isEmphasisPre(text: String, i: Int) = i == 0 || text[i - 1] in EMPHASIS_PRE
private fun isEmphasisPost(text: String, end: Int) = end >= text.length || text[end] in EMPHASIS_POST

private fun AnnotatedString.Builder.appendInline(text: String, viewMode: Boolean) {
    var i = 0
    while (i < text.length) {
        val c = text[i]
        var consumed = 0
        when (c) {
            '[' -> {
                linkRegex.matchAt(text, i)?.let { m ->
                    appendLink(m, viewMode)
                    consumed = m.value.length
                } ?: footnoteRegex.matchAt(text, i)?.let { m ->
                    withStyle(SpanStyle(color = OrgColors.footnote)) { append(m.value) }
                    consumed = m.value.length
                } ?: statisticsRegex.matchAt(text, i)?.let { m ->
                    withStyle(SpanStyle(color = OrgColors.statistics)) { append(m.value) }
                    consumed = m.value.length
                } ?: timestampRegex.matchAt(text, i)?.let { m ->
                    withStyle(SpanStyle(color = OrgColors.timestamp)) { append(m.value) }
                    consumed = m.value.length
                }
            }

            '<' -> timestampRegex.matchAt(text, i)?.let { m ->
                withStyle(SpanStyle(color = OrgColors.timestamp)) { append(m.value) }
                consumed = m.value.length
            }

            'h', 'f', 'm' -> if (i == 0 || !text[i - 1].isLetterOrDigit()) {
                urlRegex.matchAt(text, i)?.let { m ->
                    appendUrl(m.value, m.value, viewMode)
                    consumed = m.value.length
                }
            }

            '*', '/', '_', '+', '=', '~' -> if (isEmphasisPre(text, i)) {
                val spec = emphasisSpecs.getValue(c)
                spec.regex.matchAt(text, i)?.let { m ->
                    val end = i + m.value.length
                    if (isEmphasisPost(text, end)) {
                        appendEmphasis(spec, m.groupValues[1], viewMode)
                        consumed = m.value.length
                    }
                }
            }
        }
        if (consumed == 0) {
            append(c)
            i++
        } else {
            i += consumed
        }
    }
}

private fun AnnotatedString.Builder.appendEmphasis(spec: EmphasisSpec, content: String, viewMode: Boolean) {
    if (!viewMode) withStyle(SpanStyle(color = spec.markupColor)) { append(spec.marker) }
    withStyle(spec.style) {
        // Verbatim/code content is literal; other emphasis may contain nested inline constructs.
        if (spec.marker == '=' || spec.marker == '~') append(content) else appendInline(content, viewMode)
    }
    if (!viewMode) withStyle(SpanStyle(color = spec.markupColor)) { append(spec.marker) }
}

private fun AnnotatedString.Builder.appendLink(m: MatchResult, viewMode: Boolean) {
    val target = m.groupValues[1]
    val description = m.groupValues[2]
    if (viewMode) {
        appendUrl(target, description.ifEmpty { target.removePrefix("file:") }, viewMode = true)
        return
    }
    withStyle(SpanStyle(color = OrgColors.markup)) { append("[[") }
    withStyle(linkStyle) { append(target) }
    if (m.value.length > target.length + 4) {
        withStyle(SpanStyle(color = OrgColors.markup)) { append("][") }
        withStyle(linkStyle) { append(description) }
    }
    withStyle(SpanStyle(color = OrgColors.markup)) { append("]]") }
}

private fun isExternalUrl(target: String) =
    target.startsWith("http://") || target.startsWith("https://") || target.startsWith("mailto:") ||
        target.startsWith("ftp://")

// The label is appended literally: link descriptions are not scanned for nested markup.
private fun AnnotatedString.Builder.appendUrl(target: String, label: String, viewMode: Boolean) {
    if (viewMode && isExternalUrl(target)) {
        withLink(LinkAnnotation.Url(target, TextLinkStyles(style = linkStyle))) { append(label) }
    } else {
        withStyle(linkStyle) { append(label) }
    }
}

///**
// * [VisualTransformation] that applies Org-mode syntax colors to the text of a text field while
// * keeping the underlying text untouched (identity offset mapping).
// */
//class OrgSyntaxHighlightTransformation(private val headingColor: Color) : VisualTransformation {
//    override fun filter(text: AnnotatedString): TransformedText {
//        return TransformedText(
//            buildOrgHighlightedString(text.text, headingColor),
//            OffsetMapping.Identity
//        )
//    }
//}

class OrgSyntaxHighlightTransformation(private val headingColor: Color) : VisualTransformation {
    private val cache = OrgHighlightCache(headingColor)

    /**
     * Character range to style fully; lines outside it receive layout-affecting styling only. The
     * editor updates this as the viewport moves; null styles the whole document.
     *
     * A plain property rather than Compose state on purpose: [filter] is not a composable and could
     * not observe state anyway. The editor writes it during recomposition, which is exactly when
     * the field is about to re-run [filter].
     */
    var visibleChars: IntRange? = null

    override fun filter(text: AnnotatedString): TransformedText {
        val mark = kotlin.time.TimeSource.Monotonic.markNow()
        val highlighted = cache.highlight(text.text, visibleChars)
        println("[PERF] filter chars=${text.text.length} spans=${highlighted.spanStyles.size} took=${mark.elapsedNow()}")
        return TransformedText(highlighted, OffsetMapping.Identity)
    }
}
