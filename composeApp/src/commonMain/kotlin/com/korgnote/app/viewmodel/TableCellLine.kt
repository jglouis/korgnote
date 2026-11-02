package com.korgnote.app.viewmodel

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * A table's cell, edited in the grid editor, as completion reads it: the line `| cell`, which is
 * where the cell sits in the note.
 *
 * Completion's rules look at the caret's line, and a cell on its own is not one. At the start of a
 * line `*` begins a heading, `<s` a block and `#+` a keyword, none of which can be written in a
 * cell; behind the bar they are text, while `[[` is a link and `<` a date wherever they are — the
 * suggestions the element editor offers in the same cell of the same table.
 */
internal object TableCellLine {
    private const val PREFIX = "| "

    /** [cell], and its caret, as the line completion is worked out over. */
    fun of(cell: TextFieldValue): TextFieldValue = TextFieldValue(
        PREFIX + cell.text,
        TextRange(cell.selection.start + PREFIX.length, cell.selection.end + PREFIX.length),
    )

    /**
     * The cell [line] says, after completion changed it; null if the change reached into the bar,
     * which no suggestion that applies in a cell does. A line break, which no cell can hold, is a
     * space, as it would be on save.
     */
    fun cellOf(line: TextFieldValue): TextFieldValue? {
        if (!line.text.startsWith(PREFIX)) return null
        val text = line.text.substring(PREFIX.length).replace('\n', ' ')
        fun shift(offset: Int) = (offset - PREFIX.length).coerceIn(0, text.length)
        return TextFieldValue(text, TextRange(shift(line.selection.start), shift(line.selection.end)))
    }
}
