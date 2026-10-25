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
import com.korgnote.common.org.link.isNoteLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import com.korgnote.common.org.OrgDocument
import com.korgnote.common.org.OrgLineState
import com.korgnote.common.org.OrgSpan
import com.korgnote.common.org.OrgSpanKind
import com.korgnote.common.org.orgSpansForLine
import com.korgnote.common.org.OrgSyntax
import com.korgnote.common.org.OrgTodoConfig
import com.korgnote.common.org.parseTodoConfigFrom
import com.korgnote.common.org.todoKeywordsOf
import io.ktor.http.encodeURLQueryComponent
import kotlinx.datetime.LocalDate

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

/** TODO keywords used when nothing better is known: Org's own. */
val DefaultOrgTodoConfig = OrgTodoConfig(setOf("TODO"), setOf("DONE"))

/**
 * The keywords [text] declares with `#+TODO:`, or [fallback] when it declares none.
 *
 * The fallback matters more than it looks: reading mode and the element editor work on one heading
 * at a time, which never contains the file's `#+TODO:` lines, so without the file's own keywords
 * handed down as the fallback a `FINISHED` heading would not read as finished. A declaration
 * replaces the fallback rather than adding to it, as in Org and as in the calendar.
 */
fun orgTodoConfigOf(text: String, fallback: OrgTodoConfig = DefaultOrgTodoConfig): OrgTodoConfig =
    todoKeywordsOf(text, fallback)

/** As [orgTodoConfigOf] for text, from a parsed document's `#+` lines. */
fun orgTodoConfigOf(doc: OrgDocument, fallback: OrgTodoConfig = DefaultOrgTodoConfig): OrgTodoConfig =
    parseTodoConfigFrom(doc.metadata).takeUnless { it.todo.isEmpty() && it.done.isEmpty() } ?: fallback

/** Color of a TODO keyword, or null if [word] is not a known keyword. */
fun OrgTodoConfig.colorFor(word: String): Color? = when (word) {
    in done -> OrgColors.done
    in todo -> OrgColors.todo
    else -> null
}

/** True when a `:PROPERTIES:` drawer property key (as captured by [OrgSyntax.property]) is `LOCATION`. */
private fun isLocationPropertyKey(key: String) = key.trim().trim(':').equals("LOCATION", ignoreCase = true)

/** Google Maps search URL for a `LOCATION` property value, opened via the platform's [androidx.compose.ui.platform.UriHandler]. */
fun googleMapsUrl(location: String): String =
    "https://www.google.com/maps/search/?api=1&query=" + location.encodeURLQueryComponent(encodeFull = true, spaceToPlus = true)

// `\]` must stay escaped: see the note on [OrgSyntax] (JavaScript `u`-mode regexes).
/** How one emphasis marker is painted. What matches it is [OrgSyntax.emphasis]'s business. */
@Suppress("RegExpRedundantEscape")
private class EmphasisSpec(val marker: Char, val style: SpanStyle, val markupColor: Color)

/** What each emphasis marker looks like. The markers themselves are defined in [OrgSyntax]. */
private val emphasisStyles: Map<Char, EmphasisSpec> = mapOf(
    '*' to EmphasisSpec('*', SpanStyle(fontWeight = FontWeight.Bold), OrgColors.markup),
    '/' to EmphasisSpec('/', SpanStyle(fontStyle = FontStyle.Italic), OrgColors.markup),
    '_' to EmphasisSpec('_', SpanStyle(textDecoration = TextDecoration.Underline), OrgColors.markup),
    '+' to EmphasisSpec('+', SpanStyle(textDecoration = TextDecoration.LineThrough), OrgColors.markup),
    '=' to EmphasisSpec('=', SpanStyle(color = OrgColors.verbatim, fontFamily = FontFamily.Monospace), OrgColors.markup),
    '~' to EmphasisSpec('~', SpanStyle(color = OrgColors.code, fontFamily = FontFamily.Monospace), OrgColors.markup),
)

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
        val state = OrgLineState()
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

/** Heading sizes for reading mode; the editor drops them, see [editorSpanStyle]. */
private fun headingFontSize(level: Int): TextUnit = when (level) {
    1 -> 1.3.em
    2 -> 1.15.em
    3 -> 1.05.em
    else -> TextUnit.Unspecified
}

private class OrgHighlightCache(private val headingColor: Color) {
    private var sourceText: String? = null
    private var sourceResult: AnnotatedString? = null
    private var todoConfig: OrgTodoConfig? = null
    private var lines: List<String> = emptyList()
    private var lineResults: List<AnnotatedString> = emptyList()
    private var statesBefore: List<OrgLineState> = emptyList()
    private var statesAfter: List<OrgLineState> = emptyList()

    /** Styles [text], restyling only the lines an edit changed since the previous call. */
    fun highlight(text: String, fallback: OrgTodoConfig = DefaultOrgTodoConfig): AnnotatedString {
        val config = orgTodoConfigOf(text, fallback)
        if (config == todoConfig) {
            sourceResult?.let { if (sourceText == text) return it }
        }

        val newLines = text.split('\n')
        // A changed #+TODO: configuration restyles keywords anywhere in the document, so no cached
        // line survives it.
        val oldLines = if (config == todoConfig) lines else emptyList()

        val prefix = commonPrefixLength(oldLines, newLines)
        val suffix = commonSuffixLength(oldLines, newLines, prefix)

        val results = ArrayList<AnnotatedString>(newLines.size)
        val before = ArrayList<OrgLineState>(newLines.size)
        val after = ArrayList<OrgLineState>(newLines.size)
        val state = OrgLineState()

        for (i in newLines.indices) {
            val incoming = state.copy()
            before.add(incoming)
            val oldIndex = when {
                i < prefix -> i
                i >= newLines.size - suffix -> oldLines.size - (newLines.size - i)
                else -> -1
            }
            // A line's styling depends on the state it starts in, so a matching text is only
            // reusable when that matches too.
            val reusable = oldIndex >= 0 && oldIndex < lineResults.size && statesBefore[oldIndex] == incoming
            if (reusable) {
                results.add(lineResults[oldIndex])
                state.restoreFrom(statesAfter[oldIndex])
            } else {
                results.add(
                    buildAnnotatedString {
                        appendHighlightedLine(newLines[i], headingColor, config, viewMode = false, state)
                    }
                )
            }
            after.add(state.copy())
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

/**
 * Appends one line, styled from the spans `shared/` produces for it.
 *
 * All the knowledge of *what* Org constructs are now lives in `orgSpansForLine`; everything here is
 * the mapping from a kind to a colour. That is D5: one grammar, and a client that only paints.
 */
private fun AnnotatedString.Builder.appendHighlightedLine(
    line: String,
    headingColor: Color,
    todoConfig: OrgTodoConfig,
    viewMode: Boolean,
    state: OrgLineState,
) {
    // Reading mode rewrites the text as well as styling it — it hides emphasis markers and link
    // targets, and turns a LOCATION property into a link — which a list of spans over the original
    // text cannot express. That renderer still walks the line itself; see docs/08-roadmap.md (T6).
    if (viewMode) {
        appendViewModeLine(line, headingColor, todoConfig, state)
        return
    }
    val spans = orgSpansForLine(line, state)
    val base = length
    append(line)
    for (span in spans) {
        val style = spanStyleFor(span, line, headingColor, todoConfig, viewMode) ?: continue
        addStyle(style, base + span.range.first, base + span.range.last + 1)
    }
}

/** The reading-mode renderer: it rewrites the line as well as styling it. */
private fun AnnotatedString.Builder.appendViewModeLine(
    line: String,
    headingColor: Color,
    todoConfig: OrgTodoConfig,
    state: OrgLineState,
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
        appendDrawerLine(line, viewMode = true)
        return
    }
    when {
        OrgSyntax.heading.containsMatchIn(line) -> appendHeading(line, headingColor, todoConfig, true)

        OrgSyntax.blockBegin.containsMatchIn(line) -> {
            state.blockType = OrgSyntax.blockBegin.find(line)!!.groupValues[1].lowercase()
            withStyle(SpanStyle(color = OrgColors.blockDelimiter)) { append(line) }
        }

        OrgSyntax.blockEnd.containsMatchIn(line) ->
            withStyle(SpanStyle(color = OrgColors.blockDelimiter)) { append(line) }

        OrgSyntax.keyword.containsMatchIn(line) ->
            withStyle(SpanStyle(color = OrgColors.keyword)) { append(line) }

        OrgSyntax.comment.containsMatchIn(line) ->
            withStyle(SpanStyle(color = OrgColors.comment, fontStyle = FontStyle.Italic)) { append(line) }

        OrgSyntax.drawerBegin.containsMatchIn(line) -> {
            state.inDrawer = !OrgSyntax.drawerEnd.containsMatchIn(line)
            appendDrawerLine(line, viewMode = true)
        }

        OrgSyntax.planning.containsMatchIn(line) -> appendPlanning(line, viewMode = true)

        OrgSyntax.isTableLine(line) -> appendTableLine(line, viewMode = true)

        OrgSyntax.horizontalRule.containsMatchIn(line) ->
            withStyle(SpanStyle(color = OrgColors.horizontalRule)) { append(line) }

        else -> {
            val item = OrgSyntax.listItem.find(line)
            if (item != null) appendListItem(line, item, true) else appendInline(line, true)
        }
    }
}

/**
 * The colour (and, in reading mode, the weight or size) for one classified span.
 *
 * Returning null leaves the text unstyled, which is how a heading's first word stays plain when it
 * is not one of the document's TODO keywords.
 */
private fun spanStyleFor(
    span: OrgSpan,
    line: String,
    headingColor: Color,
    todoConfig: OrgTodoConfig,
    viewMode: Boolean,
): SpanStyle? = when (span.kind) {
    OrgSpanKind.Heading -> SpanStyle(
        color = headingColor,
        fontWeight = FontWeight.Bold,
        fontSize = headingFontSize(span.nesting),
    )
    OrgSpanKind.Stars -> SpanStyle(color = OrgColors.stars)
    // Whether the heading's keyword is a TODO keyword is the document's business, not the grammar's.
    OrgSpanKind.Todo -> todoConfig.colorFor(line.substring(span.range.first, span.range.last + 1).trim())
        ?.let { SpanStyle(color = it) }
    OrgSpanKind.Done -> SpanStyle(color = OrgColors.done)
    OrgSpanKind.Priority -> SpanStyle(color = OrgColors.priority)
    OrgSpanKind.Tag -> SpanStyle(color = OrgColors.tag, fontWeight = FontWeight.Normal)
    OrgSpanKind.Planning -> SpanStyle(color = OrgColors.planning)
    OrgSpanKind.PlanningKeyword -> SpanStyle(fontWeight = FontWeight.Bold)
    OrgSpanKind.Keyword -> SpanStyle(color = OrgColors.keyword)
    OrgSpanKind.Comment -> SpanStyle(color = OrgColors.comment, fontStyle = FontStyle.Italic)
    OrgSpanKind.HorizontalRule -> SpanStyle(color = OrgColors.horizontalRule)
    OrgSpanKind.Drawer -> SpanStyle(color = OrgColors.drawer)
    OrgSpanKind.DrawerKey -> SpanStyle(fontWeight = FontWeight.Bold)
    OrgSpanKind.DrawerValue -> null
    OrgSpanKind.BlockDelimiter -> SpanStyle(color = OrgColors.blockDelimiter)
    OrgSpanKind.BlockBody -> SpanStyle(background = OrgColors.blockBackground)
    OrgSpanKind.BlockBodyMonospace ->
        SpanStyle(fontFamily = FontFamily.Monospace, background = OrgColors.blockBackground)
    OrgSpanKind.BlockBodyQuote -> SpanStyle(fontStyle = FontStyle.Italic)
    OrgSpanKind.Bullet -> SpanStyle(color = OrgColors.bullet)
    OrgSpanKind.Checkbox -> SpanStyle(color = OrgColors.checkbox, fontWeight = FontWeight.Bold)
    OrgSpanKind.DescriptionTerm -> SpanStyle(color = OrgColors.descriptionTerm, fontWeight = FontWeight.Bold)
    OrgSpanKind.TableLine -> SpanStyle(fontFamily = FontFamily.Monospace)
    OrgSpanKind.TablePipe -> SpanStyle(color = OrgColors.table)
    OrgSpanKind.TableRule -> SpanStyle(color = OrgColors.tableRule)
    OrgSpanKind.Timestamp -> SpanStyle(color = OrgColors.timestamp)
    OrgSpanKind.Footnote -> SpanStyle(color = OrgColors.footnote)
    OrgSpanKind.Statistics -> SpanStyle(color = OrgColors.statistics)
    OrgSpanKind.LinkMarkup -> null
    OrgSpanKind.LinkTarget, OrgSpanKind.LinkDescription, OrgSpanKind.Url -> linkStyle
    OrgSpanKind.Markup -> if (viewMode) null else SpanStyle(color = OrgColors.markup)
    OrgSpanKind.Bold -> SpanStyle(fontWeight = FontWeight.Bold)
    OrgSpanKind.Italic -> SpanStyle(fontStyle = FontStyle.Italic)
    OrgSpanKind.Underline -> SpanStyle(textDecoration = TextDecoration.Underline)
    OrgSpanKind.StrikeThrough -> SpanStyle(textDecoration = TextDecoration.LineThrough)
    OrgSpanKind.Verbatim -> SpanStyle(color = OrgColors.verbatim, fontFamily = FontFamily.Monospace)
    OrgSpanKind.Code -> SpanStyle(color = OrgColors.code, fontFamily = FontFamily.Monospace)
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

/**
 * One styled stretch of a heading line. [inline] pieces go through [appendInline] instead of a
 * single span.
 */
private class HeadingPiece(val style: SpanStyle?, var text: String, val inline: Boolean = false)

/**
 * Folds any piece that is only whitespace into its neighbour.
 *
 * A style run holding nothing but whitespace is measured as zero width by the Skia text backend
 * (desktop, iOS and web), which made the spaces around a TODO keyword vanish while typing; Android
 * uses a different backend and never showed it. Whitespace carries no glyph, so which run owns it
 * makes no visible difference.
 */
private fun List<HeadingPiece>.foldWhitespaceIntoNeighbours() {
    forEachIndexed { index, piece ->
        if (piece.text.isEmpty() || !piece.text.isBlank()) return@forEachIndexed
        val previous = subList(0, index).lastOrNull { it.text.isNotEmpty() }
        val next = subList(index + 1, size).firstOrNull { it.text.isNotEmpty() }
        when {
            previous != null -> previous.text += piece.text
            next != null -> next.text = piece.text + next.text
            else -> return@forEachIndexed // a line of pure whitespace: nothing to fold into
        }
        piece.text = ""
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
        var rest = line.substring(stars.length)
        fun takeWhitespace(): String {
            val whitespace = rest.takeWhile { it.isWhitespace() }
            rest = rest.drop(whitespace.length)
            return whitespace
        }

        val pieces = mutableListOf(HeadingPiece(SpanStyle(color = OrgColors.stars), stars))
        var pending = takeWhitespace()

        val firstWord = rest.takeWhile { !it.isWhitespace() }
        val todoColor = todoConfig.colorFor(firstWord)
        if (todoColor != null) {
            pieces += HeadingPiece(SpanStyle(color = todoColor), pending + firstWord)
            rest = rest.drop(firstWord.length)
            pending = takeWhitespace()
        }

        val priority = OrgSyntax.priority.find(rest)
        if (priority != null) {
            pieces += HeadingPiece(SpanStyle(color = OrgColors.priority), pending + priority.value)
            rest = rest.drop(priority.value.length)
            pending = takeWhitespace()
        }

        val tags = OrgSyntax.tags.find(rest)
        val body = if (tags != null) rest.substring(0, tags.range.first) else rest
        pieces += HeadingPiece(style = null, text = pending + body, inline = true)
        if (tags != null) {
            // The whole tail: the whitespace before the tags, the tags, and any trailing spaces.
            val tagStyle = SpanStyle(color = OrgColors.tag, fontWeight = FontWeight.Normal)
            pieces += HeadingPiece(tagStyle, rest.substring(tags.range.first))
        }

        pieces.foldWhitespaceIntoNeighbours()
        for (piece in pieces) {
            when {
                piece.text.isEmpty() -> Unit
                piece.inline -> appendInline(piece.text, viewMode)
                piece.style != null -> withStyle(piece.style) { append(piece.text) }
                else -> append(piece.text)
            }
        }
    }
}

private fun AnnotatedString.Builder.appendPlanning(line: String, viewMode: Boolean) {
    withStyle(SpanStyle(color = OrgColors.planning)) {
        var pos = 0
        var keyword: String? = null
        for (m in OrgSyntax.planningKeyword.findAll(line)) {
            appendPlanningValue(line.substring(pos, m.range.first), keyword, viewMode)
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(m.value) }
            keyword = m.groupValues[1]
            pos = m.range.last + 1
        }
        appendPlanningValue(line.substring(pos), keyword, viewMode)
    }
}

/** Planning keywords whose date reading mode links to that day in the calendar. */
private val calendarLinkedPlanningKeywords = setOf("SCHEDULED", "DEADLINE")

/**
 * The text after a planning [keyword]. In reading mode a `SCHEDULED` or `DEADLINE` timestamp
 * becomes a link to its day in the calendar, the way a `LOCATION` property links to a map.
 *
 * A `<a>--<b>` range is one date, so both halves are links: each to its own day, the first to where
 * the range starts and the second to where it ends.
 */
private fun AnnotatedString.Builder.appendPlanningValue(text: String, keyword: String?, viewMode: Boolean) {
    val first = OrgSyntax.timestamp.find(text)
    if (!viewMode || keyword !in calendarLinkedPlanningKeywords || first == null || dateOfTimestamp(first.value) == null) {
        appendInline(text, viewMode)
        return
    }
    val second = first.next()?.takeIf { it.range.first == first.range.last + 3 && text.startsWith("--", first.range.last + 1) }
    appendInline(text.substring(0, first.range.first), viewMode)
    appendCalendarLink(first.value)
    if (second != null) {
        append("--")
        appendCalendarLink(second.value)
    }
    appendInline(text.substring((second ?: first).range.last + 1), viewMode)
}

/** [stamp], linked to its day in the calendar when it has a real date. */
private fun AnnotatedString.Builder.appendCalendarLink(stamp: String) {
    val day = dateOfTimestamp(stamp)
    if (day == null) append(stamp)
    else withLink(LinkAnnotation.Url(calendarDayUri(day), TextLinkStyles(style = linkStyle))) { append(stamp) }
}

private val timestampDay = Regex("\\d{4}-\\d{2}-\\d{2}")

/** The day an Org timestamp falls on, or null if its date is not a real one (`<2026-02-30>`). */
private fun dateOfTimestamp(timestamp: String): LocalDate? =
    timestampDay.find(timestamp)?.let { runCatching { LocalDate.parse(it.value) }.getOrNull() }

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
    // Indent, bullet and the whitespace after it share one run, so no whitespace forms a run of
    // its own — see the note in appendHeading.
    withStyle(SpanStyle(color = OrgColors.bullet, fontWeight = FontWeight.Bold)) {
        append(item.groupValues[1] + item.groupValues[2] + item.groupValues[3])
    }
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
        withStyle(SpanStyle(color = OrgColors.descriptionTerm, fontWeight = FontWeight.Bold)) {
            append(desc.groupValues[1])
            appendInline(desc.groupValues[2], viewMode)
        }
        withStyle(SpanStyle(color = OrgColors.markup)) { append(desc.groupValues[3]) }
        rest = rest.substring(desc.groupValues[1].length + desc.groupValues[2].length + desc.groupValues[3].length)
    }
    appendInline(rest, viewMode)
}

/** Highlights the text of a list item (without its bullet/checkbox) for reading mode. */
fun buildOrgItemString(text: String): AnnotatedString = buildAnnotatedString { appendItemText(text, viewMode = true) }

private fun isEmphasisPre(text: String, i: Int) = OrgSyntax.emphasisMayOpenAt(text, i)
private fun isEmphasisPost(text: String, end: Int) = OrgSyntax.emphasisMayCloseAt(text, end)

private fun AnnotatedString.Builder.appendInline(text: String, viewMode: Boolean) {
    var i = 0
    while (i < text.length) {
        val c = text[i]
        var consumed = 0
        when (c) {
            '[' -> {
                OrgSyntax.link.matchAt(text, i)?.let { m ->
                    appendLink(m, viewMode)
                    consumed = m.value.length
                } ?: OrgSyntax.footnoteReference.matchAt(text, i)?.let { m ->
                    withStyle(SpanStyle(color = OrgColors.footnote)) { append(m.value) }
                    consumed = m.value.length
                } ?: OrgSyntax.statistics.matchAt(text, i)?.let { m ->
                    withStyle(SpanStyle(color = OrgColors.statistics)) { append(m.value) }
                    consumed = m.value.length
                } ?: OrgSyntax.timestamp.matchAt(text, i)?.let { m ->
                    withStyle(SpanStyle(color = OrgColors.timestamp)) { append(m.value) }
                    consumed = m.value.length
                }
            }

            '<' -> OrgSyntax.timestamp.matchAt(text, i)?.let { m ->
                withStyle(SpanStyle(color = OrgColors.timestamp)) { append(m.value) }
                consumed = m.value.length
            }

            'h', 'f', 'm' -> if (i == 0 || !text[i - 1].isLetterOrDigit()) {
                OrgSyntax.url.matchAt(text, i)?.let { m ->
                    appendUrl(m.value, m.value, viewMode)
                    consumed = m.value.length
                }
            }

            '*', '/', '_', '+', '=', '~' -> if (isEmphasisPre(text, i)) {
                val spec = emphasisStyles.getValue(c)
                OrgSyntax.emphasis.getValue(c).matchAt(text, i)?.let { m ->
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
        val label = description.ifEmpty { target.removePrefix("file:") }
        if (isNoteLink(target)) {
            // Opened by the app, not the platform: see [NOTE_LINK_URI_PREFIX].
            withLink(LinkAnnotation.Url(NOTE_LINK_URI_PREFIX + target, TextLinkStyles(style = linkStyle))) {
                append(label)
            }
        } else {
            appendUrl(target, label, viewMode = true)
        }
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

/**
 * Marks a link into the notes. Reading mode renders them as ordinary URL links under this prefix,
 * and the app's [androidx.compose.ui.platform.UriHandler] takes anything carrying it — so a link
 * works wherever Org text is drawn (a note, the calendar, search results) without threading a
 * callback through every builder that draws inline text.
 */
const val NOTE_LINK_URI_PREFIX = "korgnote-link:"

/**
 * Marks a link to a day in the app's own calendar, from a `SCHEDULED` or `DEADLINE` date. Like
 * [NOTE_LINK_URI_PREFIX], it is the app's [androidx.compose.ui.platform.UriHandler] that opens it.
 */
const val CALENDAR_DAY_URI_PREFIX = "korgnote-calendar:"

/** The URI of [day] in the calendar. */
fun calendarDayUri(day: LocalDate): String = CALENDAR_DAY_URI_PREFIX + day

/** The day a [calendarDayUri] points at, or null if [uri] is not one. */
fun calendarDayOf(uri: String): LocalDate? =
    if (uri.startsWith(CALENDAR_DAY_URI_PREFIX)) {
        runCatching { LocalDate.parse(uri.removePrefix(CALENDAR_DAY_URI_PREFIX)) }.getOrNull()
    } else null

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

/**
 * The subset of a [SpanStyle] the editor may apply.
 *
 * Anything that changes text metrics — font size, weight, style or family — has to be dropped. Such
 * a span makes the text field mis-place its caret on the keystroke that first applies it: typing the
 * space that turns a line into a heading changes the line's metrics, the caret fails to advance,
 * and because a space has no glyph nothing appears to happen until the next character re-lays the
 * line out. Bold is not exempt — it selects a different font face, with its own metrics.
 *
 * Verified on desktop, iOS and web, which share the Skia text backend; Android uses a different one
 * and is unaffected.
 *
 * Colour, background fills and underline/strikethrough are kept: they are painted over the same
 * glyphs and cannot move anything.
 *
 * Reading mode does not go through [OrgSyntaxHighlightTransformation] and keeps the full styling,
 * heading sizes included.
 */
private fun editorSpanStyle(style: SpanStyle) = SpanStyle(
    color = style.color,
    background = style.background,
    textDecoration = style.textDecoration,
)

/** Same text and span ranges, with each [SpanStyle] replaced by [project] of itself. */
private fun AnnotatedString.withSpans(project: (SpanStyle) -> SpanStyle): AnnotatedString =
    AnnotatedString(
        text = text,
        spanStyles = spanStyles.map { AnnotatedString.Range(project(it.item), it.start, it.end) },
    )

class OrgSyntaxHighlightTransformation(headingColor: Color) : VisualTransformation {
    private val cache = OrgHighlightCache(headingColor)

    /**
     * Keywords for text that declares none of its own — the file's, when the text being edited is
     * one heading of it.
     *
     * A plain property rather than Compose state on purpose: [filter] is not a composable and could
     * not observe state anyway. The editor writes it during recomposition, which is exactly when
     * the field is about to re-run [filter].
     */
    var fallbackKeywords: OrgTodoConfig = DefaultOrgTodoConfig

    override fun filter(text: AnnotatedString): TransformedText {
        val highlighted = cache.highlight(text.text, fallbackKeywords).withSpans(::editorSpanStyle)
        return TransformedText(highlighted, OffsetMapping.Identity)
    }
}
