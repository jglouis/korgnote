package com.korgnote.app.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.korgnote.common.org.OrgTable
import com.korgnote.common.org.edit.TableGrid
import com.korgnote.common.org.edit.nodeAt
import com.korgnote.common.org.edit.replaceOrgTable
import com.korgnote.common.org.edit.tableFormulaLine
import com.korgnote.common.org.toOrgDocument
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.vm_finish_editing_first
import korgnote.composeapp.generated.resources.vm_table_changed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString

/**
 * A table of the open note being edited as a grid, as [TableEditing.begin] opened it: [original] is
 * its text as it was then, its `#+TBLFM:` line included.
 */
data class TableEdit(val path: String, val range: IntRange, val original: String, val grid: TableGrid)

/** Editing a table of the open note in its own editor, cell by cell. */
class TableEditing(
    private val scope: CoroutineScope,
    private val library: NoteLibrary,
    private val note: OpenNote,
    private val editor: ElementEditor,
    private val changes: NoteChanges,
    /** Shows a message to the user. */
    private val showMessage: (String) -> Unit,
) {
    /** The table being edited in its own editor, or null when none is. */
    var edit by mutableStateOf<TableEdit?>(null)
        private set

    /**
     * Opens the table whose source contains [offsetInDocument] as a grid, cell by cell: a table is
     * all but impossible to keep aligned as text on a phone. Refused while an element is being
     * edited, as the note's details are.
     */
    fun begin(offsetInDocument: Int) {
        val path = note.path ?: return
        if (editor.editingIndex != null) {
            scope.launch { showMessage(getString(Res.string.vm_finish_editing_first)) }
            return
        }
        val content = note.content
        val table = content.toOrgDocument().nodeAt<OrgTable>(offsetInDocument) ?: return
        val formulas = tableFormulaLine(content, table.range.last + 1)
        val end = formulas?.range?.let { it.last + 1 } ?: (table.range.last + 1)
        edit = TableEdit(path, table.range, content.substring(table.range.first, end), TableGrid.of(table, formulas?.formulas.orEmpty()))
    }

    fun cancel() {
        edit = null
    }

    /**
     * Writes [grid] over the table being edited, aligned, and saves. Nothing is written when nothing
     * changed — not even the alignment — or when the table's text moved while the editor was open,
     * say by a sync, since the range would then cover something else.
     */
    suspend fun commit(grid: TableGrid) {
        val editing = edit ?: return
        edit = null
        if (grid == editing.grid) return
        val content = note.content
        val end = editing.range.first + editing.original.length
        val unchanged = note.path == editing.path && end <= content.length &&
            content.substring(editing.range.first, end) == editing.original
        if (!unchanged) {
            showMessage(getString(Res.string.vm_table_changed))
            return
        }
        note.content = replaceOrgTable(content, editing.range, grid)
        changes.saveCurrentFile()
    }
}
