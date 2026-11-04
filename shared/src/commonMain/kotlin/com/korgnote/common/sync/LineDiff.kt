package com.korgnote.common.sync

import com.korgnote.common.org.OrgSyntax

/** One line of a [lineDiff]: in both versions, only the first, or only the second. */
data class DiffLine(val kind: Kind, val text: String) {
    enum class Kind { SAME, REMOVED, ADDED }
}

/** [first] turned into [second], line by line, from their longest common subsequence. */
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
