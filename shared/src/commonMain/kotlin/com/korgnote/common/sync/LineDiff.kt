package com.korgnote.common.sync

/** One line of a [lineDiff]: in both versions, only the first, or only the second. */
data class DiffLine(val kind: Kind, val text: String) {
    enum class Kind { SAME, REMOVED, ADDED }
}

/**
 * A run of changed lines with a little unchanged text around it, labelled with the Org heading it
 * falls under so a long note's differences can be told apart at a glance.
 */
data class DiffHunk(
    /** The nearest heading line at or above the hunk's first change, or null above the first heading. */
    val heading: String?,
    val lines: List<DiffLine>,
)

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

/** Only what differs between [first] and [second], with [context] unchanged lines either side. */
fun diffHunks(first: String, second: String, context: Int = 2): List<DiffHunk> {
    val lines = lineDiff(first, second)
    val changed = lines.indices.filter { lines[it].kind != DiffLine.Kind.SAME }
    if (changed.isEmpty()) return emptyList()

    // Merge changes whose context would touch, so a hunk is never split by a line or two.
    val ranges = mutableListOf<IntRange>()
    for (index in changed) {
        val start = maxOf(0, index - context)
        val end = minOf(lines.lastIndex, index + context)
        val last = ranges.lastOrNull()
        if (last != null && start <= last.last + 1) ranges[ranges.lastIndex] = last.first..end
        else ranges += start..end
    }

    return ranges.map { range ->
        // From the first change, not the first line shown: the context above a change can reach back
        // across a heading into the section before it.
        val firstChange = range.first { lines[it].kind != DiffLine.Kind.SAME }
        // Lines the second version dropped still count: they were under that heading in the first.
        val heading = (firstChange downTo 0).asSequence()
            .map { lines[it] }
            .firstOrNull { it.kind != DiffLine.Kind.ADDED && it.text.isOrgHeading() }
            ?.text
        DiffHunk(heading, lines.subList(range.first, range.last + 1))
    }
}

private fun String.isOrgHeading(): Boolean {
    val stars = takeWhile { it == '*' }.length
    return stars > 0 && getOrNull(stars) == ' '
}
