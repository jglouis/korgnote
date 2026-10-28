package com.korgnote.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.korgnote.common.org.edit.TableGrid

/**
 * A table edited cell by cell, over the whole screen.
 *
 * Each cell is a field of its own; a row's menu, at its start, and a column's, above it, insert,
 * move and remove them, and add the rule that sets a header apart. The pipes and the padding are
 * written on Save, aligned, so nothing typed here has to line anything up.
 */
@Composable
fun TableEditorDialog(
    initial: TableGrid,
    onSave: (TableGrid) -> Unit,
    onDismiss: () -> Unit,
) {
    var grid by remember { mutableStateOf(initial) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize().imePadding(), color = MaterialTheme.colorScheme.surface) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Discard changes") }
                    Text("Edit table", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onSave(grid) }) { Text("Save") }
                }
                HorizontalDivider()
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(12.dp),
                ) {
                    TableGridEditor(grid) { grid = it }
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = { grid = grid.withRowInserted(grid.rows.size) }) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Row")
                    }
                    OutlinedButton(onClick = { grid = grid.withColumnInserted(grid.columnCount) }) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Column")
                    }
                }
            }
        }
    }
}

@Composable
private fun TableGridEditor(grid: TableGrid, onChange: (TableGrid) -> Unit) {
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    // The rows above the first rule are the header, as the reading view draws them.
    val firstRule = grid.rows.indexOfFirst { it == null }
    Column {
        // A menu above each column, past the space the row menus take.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(ROW_MENU_WIDTH))
            for (column in 0 until grid.columnCount) {
                Box(Modifier.width(CELL_WIDTH), contentAlignment = Alignment.Center) {
                    ColumnMenu(column, grid, onChange)
                }
            }
        }
        grid.rows.forEachIndexed { row, cells ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RowMenu(row, grid, onChange)
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
                        BasicTextField(
                            value = text,
                            onValueChange = { onChange(grid.withCell(row, column, it)) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (firstRule > row) FontWeight.Bold else FontWeight.Normal,
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier
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
private fun RowMenu(row: Int, grid: TableGrid, onChange: (TableGrid) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.width(ROW_MENU_WIDTH)) {
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

@Composable
private fun ColumnMenu(column: Int, grid: TableGrid, onChange: (TableGrid) -> Unit) {
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
        }
    }
}

private val CELL_WIDTH = 140.dp
private val ROW_MENU_WIDTH = 48.dp
