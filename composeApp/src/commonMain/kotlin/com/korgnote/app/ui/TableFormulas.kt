package com.korgnote.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.korgnote.common.org.edit.TableGrid
import com.korgnote.common.org.table.FormulaNote
import com.korgnote.common.org.table.Recalculation
import com.korgnote.common.org.table.columnNames
import com.korgnote.common.org.table.describeFormula
import com.korgnote.common.org.table.formulaCells
import com.korgnote.common.org.table.formulaSyntaxProblem
import com.korgnote.common.org.table.recalculate
import com.korgnote.common.org.table.rowsAboveTotal
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.cancel
import korgnote.composeapp.generated.resources.close
import korgnote.composeapp.generated.resources.delete
import korgnote.composeapp.generated.resources.editor_add_formula
import korgnote.composeapp.generated.resources.editor_apply_fix
import korgnote.composeapp.generated.resources.editor_as_computed
import korgnote.composeapp.generated.resources.editor_as_written
import korgnote.composeapp.generated.resources.editor_cells
import korgnote.composeapp.generated.resources.editor_column
import korgnote.composeapp.generated.resources.editor_column_number
import korgnote.composeapp.generated.resources.editor_computes
import korgnote.composeapp.generated.resources.editor_decimals
import korgnote.composeapp.generated.resources.editor_done
import korgnote.composeapp.generated.resources.editor_edit
import korgnote.composeapp.generated.resources.editor_edit_formula
import korgnote.composeapp.generated.resources.editor_every_row
import korgnote.composeapp.generated.resources.editor_expression_placeholder
import korgnote.composeapp.generated.resources.editor_fills
import korgnote.composeapp.generated.resources.editor_formulas
import korgnote.composeapp.generated.resources.editor_gives
import korgnote.composeapp.generated.resources.editor_last_row
import korgnote.composeapp.generated.resources.editor_new_formula
import korgnote.composeapp.generated.resources.editor_no_formula_yet
import korgnote.composeapp.generated.resources.editor_one_cell
import korgnote.composeapp.generated.resources.editor_picker
import korgnote.composeapp.generated.resources.editor_recalculate
import korgnote.composeapp.generated.resources.editor_row
import korgnote.composeapp.generated.resources.editor_row_number
import korgnote.composeapp.generated.resources.editor_rows_above
import org.jetbrains.compose.resources.stringResource

/**
 * A table's formulas, each in words — its columns called by their headers — with what is wrong with
 * it, or likely a slip, and the fix when there is a safe one.
 */
@Composable
internal fun TableFormulasDialog(
    grid: TableGrid,
    notes: List<FormulaNote>,
    onChange: (TableGrid) -> Unit,
    /** Opens the editor on a formula, by its place in the list, or on a new one for null. */
    onEdit: (Int?) -> Unit,
    onRecalculate: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.editor_formulas)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (grid.formulas.isEmpty()) {
                    Text(
                        stringResource(Res.string.editor_no_formula_yet),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                for (note in notes.filter { it.formula < 0 }) {
                    NoteLine(note)
                    if (note.kind == FormulaNote.Kind.Outdated) TextButton(onClick = onRecalculate) { Text(stringResource(Res.string.editor_recalculate)) }
                }
                grid.formulas.forEachIndexed { index, formula ->
                    if (index > 0 || notes.any { it.formula < 0 }) HorizontalDivider()
                    Text(formulaWords(describeFormula(grid.rows, formula)), style = MaterialTheme.typography.bodyLarge)
                    Text(formula, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.outline)
                    for (note in notes.filter { it.formula == index }) {
                        NoteLine(note)
                        note.fix?.let { fix ->
                            TextButton(onClick = { onChange(grid.withFormulas(grid.formulas.toMutableList().also { it[index] = fix })) }) {
                                Text(stringResource(Res.string.editor_apply_fix))
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { onEdit(index) }) { Text(stringResource(Res.string.editor_edit)) }
                        TextButton(onClick = { onChange(grid.withFormulas(grid.formulas.filterIndexed { i, _ -> i != index })) }) {
                            Text(stringResource(Res.string.delete))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onEdit(null) }) { Text(stringResource(Res.string.editor_add_formula)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.close)) } },
    )
}

@Composable
private fun NoteLine(note: FormulaNote) {
    Text(
        note.message,
        style = MaterialTheme.typography.bodyMedium,
        color = when (note.kind) {
            FormulaNote.Kind.Problem -> MaterialTheme.colorScheme.error
            FormulaNote.Kind.Outdated -> MaterialTheme.colorScheme.tertiary
            FormulaNote.Kind.Suggestion -> MaterialTheme.colorScheme.primary
        },
    )
}

/** What a formula fills, as the editor offers it. */
private enum class TargetKind { Column, Cell, Other }

/**
 * One formula, edited as a form: what it fills — a whole column, or one cell — picked from the
 * table, and what it computes, written with columns inserted by name rather than typed as `$3`. What
 * it gives is shown as it is written.
 */
@Composable
internal fun FormulaEditorDialog(
    grid: TableGrid,
    /** The formula's place in the table's list, or null for a new one. */
    index: Int?,
    /** The formula as written, or what a new one starts from, as `$3=`. */
    initial: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val names = remember(grid) { columnNames(grid.rows) }
    @Composable
    fun columnLabel(column: Int) = names.getOrNull(column - 1)?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.editor_column_number, column)
    val dataLines = grid.rows.indices.filter { grid.rows[it] != null }

    val initialTarget = initial.substringBefore('=').trim()
    val initialRight = initial.substringAfter('=', "").trim()
    val decimalsFormat = Regex(""";%\.(\d)f$""")
    val initialDecimals = decimalsFormat.find(initialRight)?.groupValues?.get(1)?.toInt()

    val columnTarget = Regex("""^\$(\d+)$""").matchEntire(initialTarget)
    val cellTarget = Regex("""^@(>|\d+)\$(\d+)$""").matchEntire(initialTarget)
    var kind by remember {
        mutableStateOf(
            when {
                columnTarget != null || initialTarget.isEmpty() -> TargetKind.Column
                cellTarget != null -> TargetKind.Cell
                else -> TargetKind.Other
            },
        )
    }
    var column by remember { mutableStateOf((columnTarget?.groupValues?.get(1) ?: cellTarget?.groupValues?.get(2))?.toIntOrNull() ?: 1) }
    // The row of a one-cell formula: ">" for the last, or its number.
    var row by remember { mutableStateOf(cellTarget?.groupValues?.get(1) ?: ">") }
    var otherTarget by remember { mutableStateOf(initialTarget) }
    var expression by remember {
        val text = if (initialDecimals != null) initialRight.replace(decimalsFormat, "") else initialRight
        mutableStateOf(TextFieldValue(text, TextRange(text.length)))
    }
    var decimals by remember { mutableStateOf(initialDecimals) }

    val target = when (kind) {
        TargetKind.Column -> "\$$column"
        TargetKind.Cell -> "@$row\$$column"
        TargetKind.Other -> otherTarget.trim()
    }
    val formula = "$target=" + expression.text.trim() + (decimals?.let { ";%.${it}f" } ?: "")
    val syntax = if (expression.text.isBlank()) null else formulaSyntaxProblem(formula)

    fun insert(text: String, cursorBack: Int = 0) {
        val selection = expression.selection
        val updated = expression.text.replaceRange(selection.min, selection.max, text)
        expression = TextFieldValue(updated, TextRange(selection.min + text.length - cursorBack))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initialRight.isEmpty()) Res.string.editor_new_formula else Res.string.editor_edit_formula)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.editor_fills), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = kind == TargetKind.Column, onClick = { kind = TargetKind.Column }, label = { Text(stringResource(Res.string.editor_every_row)) })
                    FilterChip(selected = kind == TargetKind.Cell, onClick = { kind = TargetKind.Cell }, label = { Text(stringResource(Res.string.editor_one_cell)) })
                    if (cellTarget == null && columnTarget == null && initialTarget.isNotEmpty()) {
                        FilterChip(selected = kind == TargetKind.Other, onClick = { kind = TargetKind.Other }, label = { Text(stringResource(Res.string.editor_as_written)) })
                    }
                }
                when (kind) {
                    TargetKind.Other -> OutlinedTextField(
                        value = otherTarget,
                        onValueChange = { otherTarget = it },
                        label = { Text(stringResource(Res.string.editor_cells)) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    else -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Picker(
                            label = stringResource(Res.string.editor_column),
                            shown = columnLabel(column),
                            options = (1..grid.columnCount).map { it to columnLabel(it) },
                            onPick = { column = it },
                        )
                        if (kind == TargetKind.Cell) {
                            Picker(
                                label = stringResource(Res.string.editor_row),
                                shown = if (row == ">") stringResource(Res.string.editor_last_row) else stringResource(Res.string.editor_row_number, row),
                                options = listOf(">" to stringResource(Res.string.editor_last_row)) + dataLines.mapIndexed { i, line ->
                                    val first = grid.rows[line]!!.firstOrNull { it.isNotBlank() }.orEmpty()
                                    "${i + 1}" to stringResource(Res.string.editor_row_number, "${i + 1}") + if (first.isNotEmpty()) " · ${first.take(24)}" else ""
                                },
                                onPick = { row = it },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = expression,
                    onValueChange = { expression = it },
                    label = { Text(stringResource(Res.string.editor_computes)) },
                    placeholder = { Text(stringResource(Res.string.editor_expression_placeholder)) },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    isError = syntax != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                ChipRow {
                    for (c in 1..grid.columnCount) AssistChip(onClick = { insert("\$$c") }, label = { Text(columnLabel(c)) })
                }
                ChipRow {
                    if (kind == TargetKind.Cell) {
                        AssistChip(onClick = { insert(rowsAboveTotal(grid.rows)) }, label = { Text(stringResource(Res.string.editor_rows_above)) })
                    }
                    for ((label, function) in FUNCTIONS) {
                        AssistChip(onClick = { insert("$function()", cursorBack = 1) }, label = { Text(label) })
                    }
                }
                Text(stringResource(Res.string.editor_decimals), style = MaterialTheme.typography.labelLarge)
                ChipRow {
                    FilterChip(selected = decimals == null, onClick = { decimals = null }, label = { Text(stringResource(Res.string.editor_as_computed)) })
                    for (d in 0..3) FilterChip(selected = decimals == d, onClick = { decimals = d }, label = { Text("$d") })
                }
                if (expression.text.isNotBlank()) {
                    if (syntax != null) {
                        Text(syntax, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text(formulaWords(describeFormula(grid.rows, formula)), style = MaterialTheme.typography.bodyMedium)
                        Preview(grid, index, formula)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(formula) }, enabled = expression.text.isNotBlank() && target.isNotEmpty() && syntax == null) {
                Text(stringResource(Res.string.editor_done))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}

/**
 * What [formula] gives in the cells it fills, computed with the table's other formulas — a column's
 * formula gives way to a cell's own — or on its own when one of the others cannot be computed.
 */
@Composable
private fun Preview(grid: TableGrid, index: Int?, formula: String) {
    val (text, problem) = remember(grid, index, formula) {
        val all = if (index == null) grid.formulas + formula else grid.formulas.toMutableList().also { it[index] = formula }
        val position = index ?: grid.formulas.size
        val withOthers = recalculate(grid.rows, all)
        val (formulas, mine, result) = when {
            withOthers is Recalculation.Done -> Triple(all, position, withOthers)
            withOthers is Recalculation.Failed && withOthers.problems.any { it.formula == position } ->
                return@remember null to withOthers.problems.first { it.formula == position }.message
            else -> Triple(listOf(formula), 0, recalculate(grid.rows, listOf(formula)))
        }
        when (result) {
            is Recalculation.Failed -> null to result.problems.first().message
            is Recalculation.Done -> {
                val values = formulaCells(result.rows, formulas).filterValues { it == mine }.keys
                    .sortedWith(compareBy({ it.line }, { it.column }))
                    .map { result.rows[it.line]!![it.column] }
                val shown = values.take(PREVIEWED).joinToString(", ") + if (values.size > PREVIEWED) ", …" else ""
                shown to null
            }
        }
    }
    if (problem != null) Text(problem, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    if (text != null) Text(stringResource(Res.string.editor_gives, text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
private fun <T> Picker(label: String, shown: String, options: List<Pair<T, String>>, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text(stringResource(Res.string.editor_picker, label, shown), maxLines = 1) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((value, text) in options) {
                DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onPick(value) })
            }
        }
    }
}

/** A formula in words, its columns — between ‹ and › — set in bold. */
internal fun formulaWords(described: String): AnnotatedString = buildAnnotatedString {
    var rest = described
    while (true) {
        val open = rest.indexOf('‹')
        val close = rest.indexOf('›', open + 1)
        if (open < 0 || close < 0) break
        append(rest.substring(0, open))
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Color.Unspecified)) { append(rest.substring(open + 1, close)) }
        rest = rest.substring(close + 1)
    }
    append(rest)
}

private val FUNCTIONS = listOf(
    "sum" to "vsum", "mean" to "vmean", "min" to "vmin", "max" to "vmax", "count" to "vcount", "round" to "round", "if" to "if",
)

private const val PREVIEWED = 4
