package com.korgnote.common.sync

/**
 * A localized conflicting section between a note and its conflict copy.
 *
 * Represents one line-based difference region together with nearby unchanged context so a caller can
 * show where the disagreement occurs and what each side contains. The chunk is associated with the
 * nearest Org heading when one is available, making conflicts easier to locate in structured notes.
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

/**
 * Choice used to resolve one differing chunk between a note and its copy.
 *
 * A chunk can keep the note's lines, keep the copy's lines, keep both in order, or replace the
 * chunk with manually edited text. An edited choice represents no lines when its replacement text
 * is empty.
 */
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

/**
 * Resolves this choice to the lines to keep for the given conflict chunk.
 *
 * @param chunk the conflict chunk whose note or copy lines may be selected
 * @return the selected lines, manually edited lines, or an empty list when the edited text is empty
 */
fun ChunkChoice.lines(chunk: ConflictChunk): List<String> = when (this) {
    ChunkChoice.Note -> chunk.note
    ChunkChoice.Copy -> chunk.copy
    ChunkChoice.Both -> chunk.note + chunk.copy
    is ChunkChoice.Edited -> if (text.isEmpty()) emptyList() else text.split('\n')
}

/**
 * Splits the differing portions of a note and its conflict copy into independent conflict chunks.
 *
 * Each returned chunk represents one contiguous run where the two inputs differ, with up to
 * [context] unchanged lines included before and after the run to help identify its location.
 * The chunk heading is the nearest Org heading at or above the differing run when available.
 *
 * @param note the current note text to compare.
 * @param copy the conflict copy text to compare against [note].
 * @param context the maximum number of unchanged surrounding lines to include before and after each chunk.
 * @return the list of conflict chunks, in the order they appear in the compared texts.
 */
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
 * Resolves all differing chunks between a note and its conflict copy by applying the supplied
 * choices in chunk order, preserving unchanged lines and replacing each differing chunk with the
 * lines selected by its corresponding choice.
 *
 * @param note the original note text
 * @param copy the conflict copy text to compare with the note
 * @param choices the choices to apply to each differing chunk, in the same order as the chunks appear
 * @return the resolved text formed from unchanged lines and the selected replacement lines
 * @throws IllegalArgumentException if the number of choices does not match the number of differing chunks
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

/**
 * A contiguous portion of a line-based comparison between two note texts.
 *
 * [Same] represents unchanged lines shared by both texts. [Differ] represents a changed region,
 * optionally tied to the nearest Org heading, where the note text and the conflict-copy text must be
 * kept separate rather than resolved automatically.
 */
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

    for ((kind, text) in lineDiff(note, copy)) {
        when (kind) {
            DiffLine.Kind.SAME -> {
                flushDiffer()
                same += text
            }
            DiffLine.Kind.REMOVED, DiffLine.Kind.ADDED -> {
                if (inNote.isEmpty() && inCopy.isEmpty()) {
                    flushSame()
                    // A run starting on a heading the note has falls under that heading.
                    headingOfRun = if (kind == DiffLine.Kind.REMOVED && text.isOrgHeading()) text else heading
                }
                if (kind == DiffLine.Kind.REMOVED) inNote += text else inCopy += text
            }
        }
        if (kind != DiffLine.Kind.ADDED && text.isOrgHeading()) heading = text
    }
    flushDiffer()
    flushSame()
    return out
}
