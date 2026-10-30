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
import androidx.compose.material3.AlertDialog
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
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.editor_already_up_to_date
import korgnote.composeapp.generated.resources.editor_average_at_bottom
import korgnote.composeapp.generated.resources.editor_cell
import korgnote.composeapp.generated.resources.editor_cell_computed
import korgnote.composeapp.generated.resources.editor_column
import korgnote.composeapp.generated.resources.editor_column_actions
import korgnote.composeapp.generated.resources.editor_delete_column
import korgnote.composeapp.generated.resources.editor_delete_line
import korgnote.composeapp.generated.resources.editor_delete_row
import korgnote.composeapp.generated.resources.editor_discard
import korgnote.composeapp.generated.resources.editor_discard_changes
import korgnote.composeapp.generated.resources.editor_discard_table_text
import korgnote.composeapp.generated.resources.editor_discard_table_title
import korgnote.composeapp.generated.resources.editor_edit_table
import korgnote.composeapp.generated.resources.editor_formula_for_column
import korgnote.composeapp.generated.resources.editor_formulas
import korgnote.composeapp.generated.resources.editor_formulas_count
import korgnote.composeapp.generated.resources.editor_insert_column_left
import korgnote.composeapp.generated.resources.editor_insert_column_right
import korgnote.composeapp.generated.resources.editor_insert_line_below
import korgnote.composeapp.generated.resources.editor_insert_row_above
import korgnote.composeapp.generated.resources.editor_insert_row_below
import korgnote.composeapp.generated.resources.editor_keep_editing
import korgnote.composeapp.generated.resources.editor_move_down
import korgnote.composeapp.generated.resources.editor_move_left
import korgnote.composeapp.generated.resources.editor_move_right
import korgnote.composeapp.generated.resources.editor_move_up
import korgnote.composeapp.generated.resources.editor_not_recalculated
import korgnote.composeapp.generated.resources.editor_recalculate
import korgnote.composeapp.generated.resources.editor_recalculated
import korgnote.composeapp.generated.resources.editor_row
import korgnote.composeapp.generated.resources.editor_row_actions
import korgnote.composeapp.generated.resources.editor_rule
import korgnote.composeapp.generated.resources.editor_sum_at_bottom
import korgnote.composeapp.generated.resources.editor_summary
import korgnote.composeapp.generated.resources.editor_summary_outdated
import korgnote.composeapp.generated.resources.editor_summary_problems
import korgnote.composeapp.generated.resources.editor_summary_suggestions
import korgnote.composeapp.generated.resources.save
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

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
    title: String = stringResource(Res.string.editor_edit_table),
    onSave: (TableGrid) -> Unit,
    onDismiss: () -> Unit,
) {
    var grid by remember { mutableStateOf(initial) }
    // What the last Recalculate did, until the table changes again.
    var status by remember { mutableStateOf<TableStatus?>(null) }
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
                status = if (changed) TableStatus.Recalculated else TableStatus.UpToDate
            }
            is Recalculation.Failed -> {
                val failing = result.problems.distinctBy { it.formula }.size
                status = TableStatus.Failed(failing)
                showingFormulas = true
            }
        }
    }

    // Back, pressed to put the keyboard away when it is already gone, would otherwise throw the
    // whole table away: once something changed, it asks first. The close button does not.
    var confirmingDiscard by remember { mutableStateOf(false) }
    Dialog(
        onDismissRequest = { if (grid == initial) onDismiss() else confirmingDiscard = true },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize().imePadding(), color = MaterialTheme.colorScheme.surface) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.editor_discard_changes)) }
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    if (grid.formulas.isNotEmpty()) TextButton(onClick = ::recalculate) { Text(stringResource(Res.string.editor_recalculate)) }
                    TextButton(onClick = { onSave(grid) }) { Text(stringResource(Res.string.save)) }
                }
                HorizontalDivider()
                (status?.text() ?: formulaSummary(notes))?.let { message ->
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
                        Text(stringResource(Res.string.editor_row))
                    }
                    OutlinedButton(onClick = { update(grid.withColumnInserted(grid.columnCount)) }) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(Res.string.editor_column))
                    }
                    OutlinedButton(onClick = { showingFormulas = true }) {
                        Text(if (grid.formulas.isEmpty()) stringResource(Res.string.editor_formulas) else stringResource(Res.string.editor_formulas_count, grid.formulas.size))
                    }
                }
            }
        }
    }
    if (confirmingDiscard) {
        AlertDialog(
            onDismissRequest = { confirmingDiscard = false },
            title = { Text(stringResource(Res.string.editor_discard_table_title)) },
            text = { Text(stringResource(Res.string.editor_discard_table_text)) },
            confirmButton = { TextButton(onClick = { confirmingDiscard = false; onDismiss() }) { Text(stringResource(Res.string.editor_discard)) } },
            dismissButton = { TextButton(onClick = { confirmingDiscard = false }) { Text(stringResource(Res.string.editor_keep_editing)) } },
        )
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

/** What the last Recalculate did. */
private sealed interface TableStatus {
    data object Recalculated : TableStatus
    data object UpToDate : TableStatus
    /** It did nothing: this many formulas cannot be computed. */
    data class Failed(val formulas: Int) : TableStatus
}

@Composable
private fun TableStatus.text(): String = when (this) {
    TableStatus.Recalculated -> stringResource(Res.string.editor_recalculated)
    TableStatus.UpToDate -> stringResource(Res.string.editor_already_up_to_date)
    is TableStatus.Failed -> pluralStringResource(Res.plurals.editor_not_recalculated, formulas, formulas)
}

/** What the review found, in a line: what cannot be computed comes first. Null when nothing did. */
@Composable
private fun formulaSummary(notes: List<FormulaNote>): String? {
    val problems = notes.filter { it.kind == FormulaNote.Kind.Problem }.distinctBy { it.formula }.size
    val outdated = notes.any { it.kind == FormulaNote.Kind.Outdated }
    val suggestions = notes.count { it.kind == FormulaNote.Kind.Suggestion }
    val parts = buildList {
        if (problems > 0) add(pluralStringResource(Res.plurals.editor_summary_problems, problems, problems))
        if (outdated) add(stringResource(Res.string.editor_summary_outdated))
        if (suggestions > 0) add(pluralStringResource(Res.plurals.editor_summary_suggestions, suggestions, suggestions))
    }
    if (parts.isEmpty()) return null
    return stringResource(Res.string.editor_summary, parts.joinToString(" · ")).replaceFirstChar { it.uppercase() }
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
                    val ruleDescription = stringResource(Res.string.editor_rule)
                    Box(
                        Modifier
                            .width(CELL_WIDTH * grid.columnCount.coerceAtLeast(1))
                            .height(2.dp)
                            .background(MaterialTheme.colorScheme.outline)
                            .semantics { contentDescription = ruleDescription },
                    )
                } else {
                    cells.forEachIndexed { column, text ->
                        val formula = computed[FormulaCell(row, column)]
                        if (formula != null) {
                            // Filled by a formula: what it computed, shaded, and its formula a tap away.
                            val computedDescription = stringResource(Res.string.editor_cell_computed, row + 1, column + 1)
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
                                    .semantics { contentDescription = computedDescription },
                            )
                            return@forEachIndexed
                        }
                        // Brought into view once the keyboard is up: focusing the cell scrolls to
                        // it before the keyboard shrinks the screen, which can leave it underneath.
                        val requester = remember { BringIntoViewRequester() }
                        val scope = rememberCoroutineScope()
                        val cellDescription = stringResource(Res.string.editor_cell, row + 1, column + 1)
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
                                .semantics { contentDescription = cellDescription },
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
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(Res.string.editor_row_actions, row + 1))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                fun item(label: String, enabled: Boolean = true, change: () -> TableGrid) = @Composable {
                    DropdownMenuItem(
                        text = { Text(label) },
                        enabled = enabled,
                        onClick = { open = false; onChange(change()) },
                    )
                }
                item(stringResource(Res.string.editor_insert_row_above)) { grid.withRowInserted(row) }()
                item(stringResource(Res.string.editor_insert_row_below)) { grid.withRowInserted(row + 1) }()
                item(stringResource(Res.string.editor_insert_line_below)) { grid.withRuleInserted(row + 1) }()
                item(stringResource(Res.string.editor_move_up), enabled = row > 0) { grid.withRowMoved(row, up = true) }()
                item(stringResource(Res.string.editor_move_down), enabled = row < grid.rows.lastIndex) { grid.withRowMoved(row, up = false) }()
                item(stringResource(if (grid.rows[row] == null) Res.string.editor_delete_line else Res.string.editor_delete_row)) { grid.withRowRemoved(row) }()
            }
        }
    }
}

@Composable
private fun ColumnMenu(column: Int, grid: TableGrid, onChange: (TableGrid) -> Unit, onColumnFormula: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreHoriz, contentDescription = stringResource(Res.string.editor_column_actions, column + 1))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            fun item(label: String, enabled: Boolean = true, change: () -> TableGrid) = @Composable {
                DropdownMenuItem(
                    text = { Text(label) },
                    enabled = enabled,
                    onClick = { open = false; onChange(change()) },
                )
            }
            item(stringResource(Res.string.editor_insert_column_left)) { grid.withColumnInserted(column) }()
            item(stringResource(Res.string.editor_insert_column_right)) { grid.withColumnInserted(column + 1) }()
            item(stringResource(Res.string.editor_move_left), enabled = column > 0) { grid.withColumnMoved(column, left = true) }()
            item(stringResource(Res.string.editor_move_right), enabled = column < grid.columnCount - 1) { grid.withColumnMoved(column, left = false) }()
            item(stringResource(Res.string.editor_delete_column), enabled = grid.columnCount > 1) { grid.withColumnRemoved(column) }()
            HorizontalDivider()
            item(stringResource(Res.string.editor_sum_at_bottom)) { grid.withSummary(column, "vsum") }()
            item(stringResource(Res.string.editor_average_at_bottom)) { grid.withSummary(column, "vmean") }()
            DropdownMenuItem(text = { Text(stringResource(Res.string.editor_formula_for_column)) }, onClick = { open = false; onColumnFormula(column + 1) })
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
