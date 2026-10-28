package com.korgnote.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.korgnote.common.org.edit.TableGrid
import com.korgnote.common.org.table.FormulaCell
import com.korgnote.common.org.table.FormulaNote
import com.korgnote.common.org.table.Recalculation
import com.korgnote.common.org.table.formulaCells
import com.korgnote.common.org.table.reviewFormulas
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A table edited cell by cell, over the whole screen.
 *
 * Each cell is a field of its own; a row's menu, at its start, and a column's, above it, insert,
 * move and remove them, and add the rule that sets a header apart. The pipes and the padding are
 * written on Save, aligned, so nothing typed here has to line anything up.
 *
 * The table's formulas come along. The cells they fill are shaded and not typed into: tapping one
 * opens its formula. Nothing is computed until Recalculate is pressed — as in Emacs, where a table
 * is recalculated on demand — and then only when every formula can be.
 */
@Composable
fun TableEditorDialog(
    initial: TableGrid,
    title: String = "Edit table",
    onSave: (TableGrid) -> Unit,
    onDismiss: () -> Unit,
) {
    var grid by remember { mutableStateOf(initial) }
    // What the last Recalculate did, until the table changes again.
    var status by remember { mutableStateOf<String?>(null) }
    var showingFormulas by remember { mutableStateOf(false) }
    // The formula open in its editor: its place in the list, or null for a new one, and its text.
    var editingFormula by remember { mutableStateOf<Pair<Int?, String>?>(null) }
    val computed = remember(grid) { formulaCells(grid.rows, grid.formulas) }
    val notes = remember(grid) { reviewFormulas(grid.rows, grid.formulas) }

    fun update(changed: TableGrid) {
        grid = changed
        status = null
    }

    fun recalculate() {
        when (val result = grid.recalculated()) {
            is Recalculation.Done -> {
                val changed = result.rows != grid.rows
                update(grid.copy(rows = result.rows))
                status = if (changed) "Recalculated." else "Already up to date."
            }
            is Recalculation.Failed -> {
                val failing = result.problems.distinctBy { it.formula }.size
                status = "Not recalculated: " + (if (failing == 1) "a formula cannot" else "$failing formulas cannot") + " be computed."
                showingFormulas = true
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize().imePadding(), color = MaterialTheme.colorScheme.surface) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Discard changes") }
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    if (grid.formulas.isNotEmpty()) TextButton(onClick = ::recalculate) { Text("Recalculate") }
                    TextButton(onClick = { onSave(grid) }) { Text("Save") }
                }
                HorizontalDivider()
                (status ?: formulaSummary(notes))?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (notes.any { it.kind == FormulaNote.Kind.Problem }) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showingFormulas = true }
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(12.dp),
                ) {
                    TableGridEditor(
                        grid = grid,
                        computed = computed,
                        onChange = ::update,
                        onColumnFormula = { column ->
                            val existing = grid.formulas.indexOfFirst { it.substringBefore('=').trim() == "\$$column" }
                            editingFormula = if (existing >= 0) existing to grid.formulas[existing] else null to "\$$column="
                        },
                        onComputedCell = { index -> editingFormula = index to grid.formulas[index] },
                    )
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = { update(grid.withRowInserted(grid.rows.size)) }) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Row")
                    }
                    OutlinedButton(onClick = { update(grid.withColumnInserted(grid.columnCount)) }) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Column")
                    }
                    OutlinedButton(onClick = { showingFormulas = true }) {
                        Text(if (grid.formulas.isEmpty()) "Formulas" else "Formulas (${grid.formulas.size})")
                    }
                }
            }
        }
    }
    if (showingFormulas) {
        TableFormulasDialog(
            grid = grid,
            notes = notes,
            onChange = ::update,
            onEdit = { index -> editingFormula = if (index == null) null to "" else index to grid.formulas[index] },
            onRecalculate = ::recalculate,
            onDismiss = { showingFormulas = false },
        )
    }
    editingFormula?.let { (index, text) ->
        FormulaEditorDialog(
            grid = grid,
            index = index,
            initial = text,
            onSave = { formula ->
                editingFormula = null
                update(grid.withFormulas(if (index == null) grid.formulas + formula else grid.formulas.toMutableList().also { it[index] = formula }))
            },
            onDismiss = { editingFormula = null },
        )
    }
}

/** What the review found, in a line: what cannot be computed comes first. Null when nothing did. */
private fun formulaSummary(notes: List<FormulaNote>): String? {
    val problems = notes.filter { it.kind == FormulaNote.Kind.Problem }.distinctBy { it.formula }.size
    val outdated = notes.any { it.kind == FormulaNote.Kind.Outdated }
    val suggestions = notes.count { it.kind == FormulaNote.Kind.Suggestion }
    val parts = buildList {
        if (problems > 0) add(if (problems == 1) "A formula cannot be computed" else "$problems formulas cannot be computed")
        if (outdated) add("cells are out of date")
        if (suggestions > 0) add(if (suggestions == 1) "a suggestion" else "$suggestions suggestions")
    }
    if (parts.isEmpty()) return null
    return parts.joinToString(" · ").replaceFirstChar { it.uppercase() } + ". Tap to see the formulas."
}

@Composable
private fun TableGridEditor(
    grid: TableGrid,
    /** The cells formulas fill, with the formula filling each, by its place in the list. */
    computed: Map<FormulaCell, Int>,
    onChange: (TableGrid) -> Unit,
    /** Opens the formula of a column, from 1, or a new one for it. */
    onColumnFormula: (Int) -> Unit,
    /** Opens the formula that fills a cell, by its place in the list. */
    onComputedCell: (Int) -> Unit,
) {
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    val header = tableHeaderRows(grid)
    // Rows and columns numbered as formulas name them: `@2` is the second row of cells, rules aside.
    val rowNumbers = grid.rows.runningFold(0) { n, cells -> if (cells != null) n + 1 else n }.drop(1)
    Column {
        // A menu above each column, past the space the row menus take.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(ROW_MENU_WIDTH))
            for (column in 0 until grid.columnCount) {
                Row(Modifier.width(CELL_WIDTH), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Text("\$${column + 1}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    ColumnMenu(column, grid, onChange, onColumnFormula)
                }
            }
        }
        grid.rows.forEachIndexed { row, cells ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RowMenu(row, if (cells == null) null else rowNumbers[row], grid, onChange)
                if (cells == null) {
                    // A rule: a line across the table, as the reading view draws it.
                    Box(
                        Modifier
                            .width(CELL_WIDTH * grid.columnCount.coerceAtLeast(1))
                            .height(2.dp)
                            .background(MaterialTheme.colorScheme.outline)
                            .semantics { contentDescription = "Rule" },
                    )
                } else {
                    cells.forEachIndexed { column, text ->
                        val formula = computed[FormulaCell(row, column)]
                        if (formula != null) {
                            // Filled by a formula: what it computed, shaded, and its formula a tap away.
                            Text(
                                text,
                                style = MaterialTheme.typography.bodyMedium,
                                fontStyle = FontStyle.Italic,
                                fontWeight = if (row in header) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                modifier = Modifier
                                    .width(CELL_WIDTH)
                                    .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
                                    .border(0.5.dp, lineColor)
                                    .clickable { onComputedCell(formula) }
                                    .padding(horizontal = 8.dp, vertical = 10.dp)
                                    .semantics { contentDescription = "Row ${row + 1}, column ${column + 1}, computed" },
                            )
                            return@forEachIndexed
                        }
                        // Brought into view once the keyboard is up: focusing the cell scrolls to
                        // it before the keyboard shrinks the screen, which can leave it underneath.
                        val requester = remember { BringIntoViewRequester() }
                        val scope = rememberCoroutineScope()
                        BasicTextField(
                            value = text,
                            onValueChange = { onChange(grid.withCell(row, column, it)) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (row in header) FontWeight.Bold else FontWeight.Normal,
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier
                                .bringIntoViewRequester(requester)
                                .onFocusEvent { focus ->
                                    if (focus.isFocused) scope.launch {
                                        delay(KEYBOARD_SETTLES)
                                        requester.bringIntoView()
                                    }
                                }
                                .width(CELL_WIDTH)
                                .border(0.5.dp, lineColor)
                                .padding(horizontal = 8.dp, vertical = 10.dp)
                                .semantics { contentDescription = "Row ${row + 1}, column ${column + 1}" },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RowMenu(row: Int, number: Int?, grid: TableGrid, onChange: (TableGrid) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.width(ROW_MENU_WIDTH), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
        if (number != null) Text("@$number", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        Box {
            IconButton(onClick = { open = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Row ${row + 1} actions")
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                fun item(label: String, enabled: Boolean = true, change: () -> TableGrid) = @Composable {
                    DropdownMenuItem(
                        text = { Text(label) },
                        enabled = enabled,
                        onClick = { open = false; onChange(change()) },
                    )
                }
                item("Insert row above") { grid.withRowInserted(row) }()
                item("Insert row below") { grid.withRowInserted(row + 1) }()
                item("Insert line below") { grid.withRuleInserted(row + 1) }()
                item("Move up", enabled = row > 0) { grid.withRowMoved(row, up = true) }()
                item("Move down", enabled = row < grid.rows.lastIndex) { grid.withRowMoved(row, up = false) }()
                item(if (grid.rows[row] == null) "Delete line" else "Delete row") { grid.withRowRemoved(row) }()
            }
        }
    }
}

@Composable
private fun ColumnMenu(column: Int, grid: TableGrid, onChange: (TableGrid) -> Unit, onColumnFormula: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreHoriz, contentDescription = "Column ${column + 1} actions")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            fun item(label: String, enabled: Boolean = true, change: () -> TableGrid) = @Composable {
                DropdownMenuItem(
                    text = { Text(label) },
                    enabled = enabled,
                    onClick = { open = false; onChange(change()) },
                )
            }
            item("Insert column left") { grid.withColumnInserted(column) }()
            item("Insert column right") { grid.withColumnInserted(column + 1) }()
            item("Move left", enabled = column > 0) { grid.withColumnMoved(column, left = true) }()
            item("Move right", enabled = column < grid.columnCount - 1) { grid.withColumnMoved(column, left = false) }()
            item("Delete column", enabled = grid.columnCount > 1) { grid.withColumnRemoved(column) }()
            HorizontalDivider()
            item("Sum at bottom") { grid.withSummary(column, "vsum") }()
            item("Average at bottom") { grid.withSummary(column, "vmean") }()
            DropdownMenuItem(text = { Text("Formula for this column…") }, onClick = { open = false; onColumnFormula(column + 1) })
        }
    }
}

/**
 * The rows drawn bold, as the reading view picks them: from the first row of cells to the rule
 * under it. None when no rule follows, since then nothing sets a header apart.
 */
internal fun tableHeaderRows(grid: TableGrid): IntRange {
    val first = grid.rows.indexOfFirst { it != null }
    if (first < 0) return IntRange.EMPTY
    val rule = grid.rows.withIndex().firstOrNull { (i, cells) -> i > first && cells == null }?.index ?: return IntRange.EMPTY
    return first until rule
}

/** Long enough for the keyboard to finish sliding in, and the screen to shrink for it. */
private const val KEYBOARD_SETTLES = 350L

private val CELL_WIDTH = 140.dp
private val ROW_MENU_WIDTH = 72.dp
