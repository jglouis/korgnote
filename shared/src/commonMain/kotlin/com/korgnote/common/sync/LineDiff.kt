package com.korgnote.common.sync

import com.korgnote.common.org.OrgSyntax

/**
 * Represents a single line in a line-based diff.
 *
 * A diff line records the line content together with how that line relates to the compared text:
 * unchanged, removed, or added. It is intended for presenting or processing textual differences
 * without embedding merge conflict markers in the text itself.
 */
data class DiffLine(val kind: Kind, val text: String) {
    enum class Kind { SAME, REMOVED, ADDED }
}

/**
 * Computes a line-based diff between two strings.
 *
 * The result preserves the order of lines as they appear in the compared text. Lines present in
 * both inputs are marked as [DiffLine.Kind.SAME], lines present only in [first] are marked as
 * [DiffLine.Kind.REMOVED], and lines present only in [second] are marked as [DiffLine.Kind.ADDED].
 *
 * @param first the original text to compare from.
 * @param second the revised text to compare against.
 * @return the ordered list of diff lines describing unchanged, removed, and added lines.
 */
fun lineDiff(first: String, second: String): List<DiffLine> {
    val a = first.lineSequence().toList()
    val b = second.lineSequence().toList()
    val toSecond = alignment(a, b)

    val out = mutableListOf<DiffLine>()
    var j = 0
    for (i in a.indices) {
        val match = toSecond[i]
        if (match == null) {
            out += DiffLine(DiffLine.Kind.REMOVED, a[i])
            continue
        }
        while (j < match) out += DiffLine(DiffLine.Kind.ADDED, b[j++])
        out += DiffLine(DiffLine.Kind.SAME, a[i])
        j++
    }
    while (j < b.size) out += DiffLine(DiffLine.Kind.ADDED, b[j++])
    return out
}

internal fun String.isOrgHeading(): Boolean = OrgSyntax.heading.containsMatchIn(this)
