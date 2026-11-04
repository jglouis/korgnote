package com.korgnote.common.org.capture

import com.korgnote.common.org.OrgSyntax
import com.korgnote.common.org.complete.orgStamp
import com.korgnote.common.org.edit.orgLinkOf
import kotlinx.datetime.LocalDateTime

/** An entry ready to file: its text, and where the caret goes in it when it is edited first. */
data class RenderedCapture(val text: String, val cursor: Int)

/**
 * [entry] filled in with [captured] at [now]. The escapes are org-capture's where it
 * has one, and those org-protocol fills for a link:
 *
 * | Escape | Becomes |
 * |---|---|
 * | `%:description` | the title |
 * | `%:link` | the link |
 * | `%a` | the link with the title, `[[link][title]]`; nothing without a link |
 * | `%i` | the text, each line after the first indented as `%i` is |
 * | `%:location`, `%:geo`, `%:when` | where, coordinates, when — KorgNote's own |
 * | `%:scheduled` | `SCHEDULED: <…>` for an event at one time; nothing for one across days |
 * | `%:range` | an event across days, `<…>--<…>`, which Org cannot schedule |
 * | `%U`, `%u`, `%T`, `%t` | now: inactive with the time, inactive, active with the time, active |
 * | `%^{Name}`, `%^{Name\|default}` | the answer in [prompts], or the default |
 * | `%?` | nothing: the caret goes there |
 * | `%%` | `%` |
 *
 * A line whose escapes all came to nothing goes, since it was about something not shared — a
 * `:LOCATION:` for a page, a link for a thought — and then a property drawer left empty. A
 * heading stays whatever it became: an entry is always one.
 */
fun renderCapture(
    entry: String,
    captured: Captured,
    now: LocalDateTime,
    prompts: Map<String, String> = emptyMap(),
): RenderedCapture {
    val annotation = captured.link?.let { orgLinkOf(it, captured.title) }
    val lines = mutableListOf<String>()
    for (line in entry.lines()) {
        val out = StringBuilder()
        var escapes = 0
        var filled = 0
        var i = 0
        while (i < line.length) {
            val match = ESCAPE.matchAt(line, i)
            if (match == null) {
                out.append(line[i])
                i++
                continue
            }
            val token = match.value
            val value: String? = when {
                token == "%%" -> "%"
                token == "%?" -> CARET.toString()
                token.startsWith("%^{") -> {
                    val spec = token.substring(3, token.length - 1).split('|')
                    prompts[spec[0]] ?: spec.getOrNull(1).orEmpty()
                }
                else -> when (token) {
                    "%:description" -> oneLine(captured.title)
                    "%:link" -> captured.link.orEmpty()
                    "%a" -> annotation.orEmpty()
                    "%i" -> indented(captured.text, indent = out.takeWhile { it == ' ' || it == '\t' }.toString())
                    "%:location" -> oneLine(captured.location.orEmpty())
                    "%:geo" -> captured.geo.orEmpty()
                    "%:when" -> captured.time.orEmpty()
                    "%:scheduled" -> captured.schedulable?.let { "SCHEDULED: $it" }.orEmpty()
                    "%:range" -> captured.range.orEmpty()
                    "%U" -> now.orgStamp(active = false)
                    "%u" -> now.date.orgStamp(active = false)
                    "%T" -> now.orgStamp(active = true)
                    "%t" -> now.date.orgStamp(active = true)
                    // Not one of ours: kept as written, so a template meant for Emacs is not mangled.
                    else -> null
                }
            }
            if (value == null) {
                out.append(token)
            } else {
                if (token != "%?" && token != "%%") {
                    escapes++
                    if (value.isNotEmpty()) filled++
                }
                out.append(value)
            }
            i += token.length
        }
        if (OrgSyntax.heading.containsMatchIn(line)) {
            // Still a heading with nothing after its stars: `* ` and not `*`, which is not one.
            val heading = out.toString().trimEnd()
            lines += if (OrgSyntax.heading.containsMatchIn(heading)) heading else "$heading "
        } else if (escapes == 0 || filled > 0) {
            lines += out.toString()
        }
    }
    val joined = withoutEmptyDrawers(lines).dropLastWhile { it.isBlank() }.joinToString("\n") + "\n"
    val caret = joined.indexOf(CARET)
    val text = joined.replace(CARET.toString(), "")
    // Where the caret was asked for, or the end of the heading, where a title gets typed.
    return RenderedCapture(text, if (caret >= 0) caret else text.indexOf('\n').coerceAtLeast(0))
}

/** Marks `%?` until the lines around it have settled; a private-use character, never in a note. */
private const val CARET = '\uE000'

/** `%:description` and the like, `%^{Prompt|default}`, or a one-letter escape. */
private val ESCAPE = Regex("%(?::[a-z]+|\\^\\{[^}]*\\}|[a-zA-Z?%])")

/** [text] on one line, its line breaks read as spaces: what a heading's title or a property can hold. */
internal fun oneLine(text: String) = text.replace(LINE_BREAK, " ").trim()

private val LINE_BREAK = Regex("\\s*\\n\\s*")

/**
 * [text] for `%i`: each line after the first given [indent], and a line that would start a heading
 * pushed in by a space — a list item to Org, not a heading — so shared text can never cut the entry
 * in two.
 */
private fun indented(text: String, indent: String): String =
    text.trim('\n').lines().mapIndexed { index, line ->
        val safe = if (OrgSyntax.heading.containsMatchIn(line)) " $line" else line
        if (index == 0 || safe.isEmpty()) safe else indent + safe
    }.joinToString("\n")

/** [lines] without a `:PROPERTIES:` drawer that has nothing left in it. */
private fun withoutEmptyDrawers(lines: List<String>): List<String> {
    val out = mutableListOf<String>()
    var i = 0
    while (i < lines.size) {
        if (lines[i].trim().equals(":PROPERTIES:", ignoreCase = true) &&
            i + 1 < lines.size && lines[i + 1].trim().equals(":END:", ignoreCase = true)
        ) {
            i += 2
            continue
        }
        out += lines[i]
        i++
    }
    return out
}

/**
 * [file] with [entry] filed at its end.
 *
 * Only ever adds lines after the existing ones, so two devices that each filed one while apart both
 * keep theirs: [com.korgnote.common.sync.merge] keeps two insertions at one place.
 */
fun fileCapture(file: String, entry: String): String {
    val body = entry.trimEnd('\n') + "\n"
    return if (file.isEmpty()) body else file.removeSuffix("\n") + "\n" + body
}
