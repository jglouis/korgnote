package com.korgnote.common.sync

/**
 * One place where a note and its conflict copy differ, settled on its own: a run of changed lines
 * between lines both versions share, as `git add -p` offers them.
 *
 * Whole runs rather than single lines: the lines of one run are usually one edit — a heading and
 * its state, a drawer, a block — and taking half of it is how an Org file ends up with a heading
 * that lost its body or a `#+BEGIN_SRC` that never ends. Anything finer is what [ChunkChoice.Edited]
 * is for.
 */
data class ConflictChunk(
    /** The nearest heading at or above the chunk in the note, or null above the first heading. */
    val heading: String?,
    /** Unchanged lines just above, to tell where the chunk is. */
    val before: List<String>,
    /** What the note has here; empty when only the copy has lines here. */
    val note: List<String>,
    /** What the copy has here; empty when only the note has lines here. */
    val copy: List<String>,
    /** Unchanged lines just below. */
    val after: List<String>,
)

/** What to keep of one [ConflictChunk]. */
sealed interface ChunkChoice {
    /** The note's lines. */
    data object Note : ChunkChoice

    /** The copy's lines. */
    data object Copy : ChunkChoice

    /** The note's lines, then the copy's. */
    data object Both : ChunkChoice

    /** Lines written by hand in their place; nothing at all when [text] is empty. */
    data class Edited(val text: String) : ChunkChoice
}

/** The lines [choice] keeps of [chunk]. */
fun ChunkChoice.lines(chunk: ConflictChunk): List<String> = when (this) {
    ChunkChoice.Note -> chunk.note
    ChunkChoice.Copy -> chunk.copy
    ChunkChoice.Both -> chunk.note + chunk.copy
    is ChunkChoice.Edited -> if (text.isEmpty()) emptyList() else text.split('\n')
}

/** Where [note] and [copy] differ, in order, each with [context] unchanged lines either side. */
fun conflictChunks(note: String, copy: String, context: Int = 2): List<ConflictChunk> {
    val pieces = pieces(note, copy)
    return pieces.mapIndexedNotNull { index, piece ->
        if (piece !is Piece.Differ) return@mapIndexedNotNull null
        val above = (pieces.getOrNull(index - 1) as? Piece.Same)?.lines.orEmpty()
        val below = (pieces.getOrNull(index + 1) as? Piece.Same)?.lines.orEmpty()
        ConflictChunk(piece.heading, above.takeLast(context), piece.note, piece.copy, below.take(context))
    }
}

/**
 * The note as it is once each chunk of [conflictChunks] for [note] and [copy] is settled by the
 * choice at its index in [choices]. Every chunk the note's way gives the note back; every one the
 * copy's way gives the copy.
 */
fun resolveConflict(note: String, copy: String, choices: List<ChunkChoice>): String {
    val pieces = pieces(note, copy)
    val differing = pieces.count { it is Piece.Differ }
    require(choices.size == differing) { "${choices.size} choices for $differing chunks" }

    val out = mutableListOf<String>()
    var chunk = 0
    for (piece in pieces) {
        when (piece) {
            is Piece.Same -> out += piece.lines
            is Piece.Differ -> {
                val asChunk = ConflictChunk(piece.heading, emptyList(), piece.note, piece.copy, emptyList())
                out += choices[chunk++].lines(asChunk)
            }
        }
    }
    return out.joinToString("\n")
}

/** [note] and [copy] cut into the runs they share and the runs where they differ. */
private sealed interface Piece {
    data class Same(val lines: List<String>) : Piece
    data class Differ(val heading: String?, val note: List<String>, val copy: List<String>) : Piece
}

private fun pieces(note: String, copy: String): List<Piece> {
    val out = mutableListOf<Piece>()
    val same = mutableListOf<String>()
    val inNote = mutableListOf<String>()
    val inCopy = mutableListOf<String>()
    // The heading the note is under so far: what was in it before this point, removed lines included.
    var heading: String? = null
    var headingOfRun: String? = null

    fun flushSame() {
        if (same.isNotEmpty()) out += Piece.Same(same.toList()).also { same.clear() }
    }
    fun flushDiffer() {
        if (inNote.isNotEmpty() || inCopy.isNotEmpty()) {
            out += Piece.Differ(headingOfRun, inNote.toList(), inCopy.toList())
            inNote.clear(); inCopy.clear()
        }
    }

    for (line in lineDiff(note, copy)) {
        when (line.kind) {
            DiffLine.Kind.SAME -> {
                flushDiffer()
                same += line.text
            }
            DiffLine.Kind.REMOVED, DiffLine.Kind.ADDED -> {
                if (inNote.isEmpty() && inCopy.isEmpty()) {
                    flushSame()
                    // A run starting on a heading the note has falls under that heading.
                    headingOfRun = if (line.kind == DiffLine.Kind.REMOVED && line.text.isOrgHeading()) line.text else heading
                }
                if (line.kind == DiffLine.Kind.REMOVED) inNote += line.text else inCopy += line.text
            }
        }
        if (line.kind != DiffLine.Kind.ADDED && line.text.isOrgHeading()) heading = line.text
    }
    flushDiffer()
    flushSame()
    return out
}
