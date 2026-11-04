package com.korgnote.common.org.edit

import com.korgnote.common.org.OrgSyntax
import com.korgnote.common.org.complete.CompletionResult

/**
 * What Enter does on a list item, as Org's M-RET does it: the new line starts the next item.
 *
 * [before] is the text and [caret] where it was before the edit; [after] is the text with Enter's
 * newline in it. When that newline ended a list item, the result carries the next item's bullet —
 * the same bullet, the next number of an ordered list, an empty box after a checkbox — with the caret
 * after it. Enter on an item with nothing in it ends the list instead: the empty bullet goes, as in
 * any editor that continues lists, so there is a way out of one.
 *
 * Null when the edit was not a single newline typed after a list item, which leaves it as typed.
 */
fun continueList(before: String, caret: Int, after: String): CompletionResult? {
    if (caret !in 0..before.length || after.length != before.length + 1) return null
    if (after[caret] != '\n') return null
    if (!after.startsWith(before.substring(0, caret)) || !after.endsWith(before.substring(caret))) return null

    val lineStart = before.lastIndexOf('\n', caret - 1) + 1
    val line = before.substring(lineStart, caret)
    // Enter in the middle of an item splits it: what follows the caret becomes the next item.
    val rest = before.substring(caret).substringBefore('\n')
    val item = OrgSyntax.listItem.find(line) ?: return null
    // In a source or example block the text is code: a `- ` in YAML is not an Org list.
    if (insideVerbatimBlock(before.substring(0, lineStart))) return null
    val indent = item.groupValues[1]
    val bullet = item.groupValues[2]
    val box = item.groupValues[4]
    val content = line.substring(item.value.length)

    if (content.isBlank() && rest.isBlank()) {
        // An empty item: Enter ends the list, leaving the line empty and the caret on it.
        val text = before.substring(0, lineStart) + before.substring(caret)
        return CompletionResult(text, lineStart)
    }

    val next = buildString {
        append(indent)
        append(nextBullet(bullet, counter = OrgSyntax.listCounter.find(item.value)?.groupValues?.get(1)?.toIntOrNull()))
        append(' ')
        if (box.isNotEmpty()) append("[ ] ")
    }
    val text = after.substring(0, caret + 1) + next + after.substring(caret + 1)
    return CompletionResult(text, caret + 1 + next.length)
}

/**
 * `-` stays `-`; `3.` becomes `4.`, `3)` becomes `4)`, or what follows its [counter] when it has one,
 * since a `[@5]` numbers the item whatever it writes.
 */
private fun nextBullet(bullet: String, counter: Int?): String {
    val number = bullet.dropLast(1).toIntOrNull() ?: return bullet
    return "${(counter ?: number) + 1}${bullet.last()}"
}

/** Blocks whose body Org reads as it is, not as Org: lists in them are someone else's syntax. */
private val VERBATIM_BLOCKS = setOf("SRC", "EXAMPLE", "EXPORT", "COMMENT")

/** Whether the lines in [text] leave a verbatim block open. */
private fun insideVerbatimBlock(text: String): Boolean {
    var open: String? = null
    for (line in text.lineSequence()) {
        if (open == null) {
            open = OrgSyntax.blockBegin.find(line)?.groupValues?.get(1)?.uppercase()
        } else if (OrgSyntax.blockEnd.containsMatchIn(line)) {
            open = null
        }
    }
    return open in VERBATIM_BLOCKS
}
