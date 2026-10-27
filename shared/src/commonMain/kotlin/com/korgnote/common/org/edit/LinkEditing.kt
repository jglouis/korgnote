package com.korgnote.common.org.edit

import com.korgnote.common.org.OrgSyntax
import com.korgnote.common.org.complete.CompletionResult
import com.korgnote.common.org.link.isNoteLink

/**
 * A link in an element being edited, as the link form shows it: where it is, where it goes, and
 * the words it shows in place of that. A bare web address is one too, with no description yet.
 */
data class DraftLink(
    /** Where the link starts in the draft. */
    val start: Int,
    /** Where it ends, exclusive. */
    val end: Int,
    val target: String,
    /** Empty when the link shows its target, as `[[https://…]]` and a bare address do. */
    val description: String,
)

/** What a link leads to, as the link form groups it: each kind is chosen in its own way. */
enum class LinkKind {
    /** A web address — or any other scheme, which the system opens. */
    WEB,

    /** A note or a heading in one: `id:`, `file:` to an `.org`, a heading's title. */
    NOTE,

    /** A file outside the notes: `file:~/Sync/x.pdf`, or a bare path to one. */
    FILE,
}

fun linkKindOf(target: String): LinkKind {
    val t = target.trim()
    val scheme = SCHEME.find(t)?.groupValues?.get(1)?.lowercase()
    return when {
        scheme in FILE_SCHEMES -> if (isNoteLink(t)) LinkKind.NOTE else LinkKind.FILE
        scheme == null && (t.startsWith("/") || t.startsWith("~") || t.startsWith("./") || t.startsWith("../")) ->
            if (isNoteLink(t)) LinkKind.NOTE else LinkKind.FILE
        scheme == null || scheme == "id" -> LinkKind.NOTE
        else -> LinkKind.WEB
    }
}

/**
 * The link the caret at [caret] is in, or null. Inside the brackets of `[[…][…]]`, not just before
 * them, so that a link can still be added next to one; anywhere on a bare address, its end
 * included, where the caret is left after typing or pasting one.
 */
fun linkAt(text: String, caret: Int): DraftLink? {
    val lineStart = text.lastIndexOf('\n', caret - 1) + 1
    val lineEnd = text.indexOf('\n', caret).let { if (it < 0) text.length else it }
    val line = text.substring(lineStart, lineEnd)
    val at = caret - lineStart
    val links = OrgSyntax.link.findAll(line).toList()
    links.firstOrNull { at > it.range.first && at <= it.range.last }?.let { m ->
        return DraftLink(lineStart + m.range.first, lineStart + m.range.last + 1, m.groupValues[1], m.groupValues[2])
    }
    return OrgSyntax.url.findAll(line)
        .filter { url -> links.none { url.range.first in it.range } }
        .firstOrNull { at >= it.range.first && at <= it.range.last + 1 }
        ?.let { DraftLink(lineStart + it.range.first, lineStart + it.range.last + 1, it.value, "") }
}

/**
 * `[[target][description]]` as Org writes it, or `[[target]]` when there are no words to show
 * instead. Neither may hold the brackets that would end the link early: a target's are written as
 * `%5B` and `%5D`, as a browser would, and a description's become parentheses, as in
 * [com.korgnote.common.org.link.LinkTarget.linkWith]. A description is one line.
 */
fun orgLinkOf(target: String, description: String): String {
    val t = target.trim().replace("[", "%5B").replace("]", "%5D")
    val d = description.replace('[', '(').replace(']', ')').replace(Regex("\\s+"), " ").trim()
    return if (d.isEmpty() || d == t) "[[$t]]" else "[[$t][$d]]"
}

/** [text] with [start]–[end] replaced by the link [orgLinkOf] writes, and the caret after it. */
fun withLink(text: String, start: Int, end: Int, target: String, description: String): CompletionResult {
    val link = orgLinkOf(target, description)
    return CompletionResult(text.replaceRange(start, end, link), start + link.length)
}

/**
 * [after] with a web address that was just pasted into [before] written as an Org link, or null
 * when nothing like that happened.
 *
 * A paste is the address arriving whole, in place of the selection [selectionStart]–[selectionEnd]:
 * typing brings it a letter at a time. Pasted over words, those words become what the link shows.
 * It stays bare where Org wants it bare — a property such as org-roam's `:ROAM_REFS:`, a `#+`
 * keyword line, a block — and where it is already part of a link being typed.
 */
fun pastedLink(before: String, selectionStart: Int, selectionEnd: Int, after: String): CompletionResult? {
    val from = minOf(selectionStart, selectionEnd).coerceIn(0, before.length)
    val to = maxOf(selectionStart, selectionEnd).coerceIn(0, before.length)
    val prefix = before.substring(0, from)
    val suffix = before.substring(to)
    if (after.length <= prefix.length + suffix.length || !after.startsWith(prefix) || !after.endsWith(suffix)) return null
    val inserted = after.substring(prefix.length, after.length - suffix.length)
    val address = inserted.trim()
    if (!WEB_ADDRESS.matches(address) || address.length < 2 || !OrgSyntax.url.matches(address)) return null

    val lineStart = prefix.lastIndexOf('\n') + 1
    val lineBefore = prefix.substring(lineStart)
    if (KEYWORD_LINE.containsMatchIn(lineBefore) || PROPERTY_LINE.containsMatchIn(lineBefore)) return null
    if (lineBefore.endsWith("<") || lineBefore.lastIndexOf("[[") > lineBefore.lastIndexOf("]]")) return null
    if (insideBlock(prefix.substring(0, lineStart))) return null

    val selected = before.substring(from, to)
    val description = if ('\n' in selected) "" else selected
    val lead = inserted.substring(0, inserted.indexOf(address))
    val trail = inserted.substring(lead.length + address.length)
    val link = orgLinkOf(address, description)
    return CompletionResult(prefix + lead + link + trail + suffix, prefix.length + lead.length + link.length)
}

/** Whether the lines in [text] leave a `#+BEGIN_` block open. */
private fun insideBlock(text: String): Boolean {
    var open = false
    for (line in text.lineSequence()) {
        if (!open && OrgSyntax.blockBegin.containsMatchIn(line)) open = true
        else if (open && OrgSyntax.blockEnd.containsMatchIn(line)) open = false
    }
    return open
}

private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+-]*):(?!\\s)")
private val FILE_SCHEMES = setOf("file", "file+sys", "file+emacs")
private val WEB_ADDRESS = Regex("^(?:https?|ftp)://\\S+$|^mailto:\\S+$", RegexOption.IGNORE_CASE)
private val KEYWORD_LINE = Regex("^\\s*#\\+")
private val PROPERTY_LINE = Regex("^\\s*:[A-Za-z0-9_+-]+:")
