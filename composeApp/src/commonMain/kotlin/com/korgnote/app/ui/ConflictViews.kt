package com.korgnote.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.korgnote.app.viewmodel.ConflictComparison
import com.korgnote.common.sync.ChunkChoice
import com.korgnote.common.sync.ConflictChunk
import com.korgnote.common.sync.lines
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.close
import korgnote.composeapp.generated.resources.notes_conflict_all_copy
import korgnote.composeapp.generated.resources.notes_conflict_all_note
import korgnote.composeapp.generated.resources.notes_conflict_apply
import korgnote.composeapp.generated.resources.notes_conflict_edit_label
import korgnote.composeapp.generated.resources.notes_conflict_left
import korgnote.composeapp.generated.resources.notes_conflict_resolve_hint
import korgnote.composeapp.generated.resources.notes_conflict_take_both
import korgnote.composeapp.generated.resources.notes_conflict_take_copy
import korgnote.composeapp.generated.resources.notes_conflict_take_edit
import korgnote.composeapp.generated.resources.notes_conflict_take_note
import korgnote.composeapp.generated.resources.notes_conflict_before_first_heading
import korgnote.composeapp.generated.resources.notes_conflict_compare
import korgnote.composeapp.generated.resources.notes_conflict_compare_title
import korgnote.composeapp.generated.resources.notes_conflict_copy_text
import korgnote.composeapp.generated.resources.notes_conflict_copy_title
import korgnote.composeapp.generated.resources.notes_conflict_count
import korgnote.composeapp.generated.resources.notes_conflict_discard_this
import korgnote.composeapp.generated.resources.notes_conflict_keep_this
import korgnote.composeapp.generated.resources.notes_conflict_menu_title
import korgnote.composeapp.generated.resources.notes_conflict_note_text
import korgnote.composeapp.generated.resources.notes_conflict_note_text_more
import korgnote.composeapp.generated.resources.notes_conflict_note_title
import korgnote.composeapp.generated.resources.notes_conflict_only_in_copy
import korgnote.composeapp.generated.resources.notes_conflict_only_in_note
import korgnote.composeapp.generated.resources.notes_conflict_same
import korgnote.composeapp.generated.resources.notes_conflict_side_folder
import korgnote.composeapp.generated.resources.notes_conflict_side_server
import korgnote.composeapp.generated.resources.notes_conflict_use_other
import korgnote.composeapp.generated.resources.notes_conflict_use_this
import korgnote.composeapp.generated.resources.notes_conflict_versions
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Notes with an unresolved conflict, for the top bar: nothing while there are none, a warning with
 * their count while there are. A tap lists them; picking one opens it, where its banner says what
 * to do.
 *
 * Not quiet like being offline: a conflict means two versions of a note exist and somebody has to
 * choose, and nothing will choose for them.
 */
@Composable
fun ConflictStatusIcon(conflicts: Map<String, List<String>>, onOpen: (path: String) -> Unit) {
    if (conflicts.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            BadgedBox(badge = { Badge { Text("${conflicts.size}") } }) {
                Icon(
                    Icons.Filled.SyncProblem,
                    contentDescription = stringResource(Res.string.notes_conflict_count, conflicts.size),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                stringResource(Res.string.notes_conflict_menu_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            for ((path, copies) in conflicts.entries.sortedBy { it.key }) {
                DropdownMenuItem(
                    text = { Text(if (copies.size == 1) path else stringResource(Res.string.notes_conflict_versions, path, copies.size)) },
                    onClick = { open = false; onOpen(path) },
                )
            }
        }
    }
}

/** The conflict the banner shows for the open note: a note and one of its copies. */
data class ShownConflict(
    val original: String,
    val copy: String,
    /** True when the copy is what is open, rather than the note. */
    val viewingCopy: Boolean,
    /** Copies beyond [copy]; each gets its turn once this one is settled. */
    val moreCopies: Int,
)

/**
 * What the banner says for [openPath]: on a note, its newest copy and how many more there are; on
 * a copy, its own note. Null when neither is in conflict.
 */
fun conflictShown(openPath: String?, conflicts: Map<String, List<String>>): ShownConflict? {
    if (openPath == null) return null
    conflicts[openPath]?.firstOrNull()?.let { newest ->
        return ShownConflict(openPath, newest, viewingCopy = false, moreCopies = conflicts.getValue(openPath).size - 1)
    }
    val original = conflicts.entries.firstOrNull { openPath in it.value }?.key ?: return null
    return ShownConflict(original, openPath, viewingCopy = true, moreCopies = 0)
}

/**
 * Above a note in conflict: which version is on screen, where the other one is, and the three ways
 * out. Shown on the note itself and on a copy, worded for whichever is open, with the same actions
 * either way. See [conflictShown].
 */
@Composable
fun ConflictBanner(
    conflict: ShownConflict,
    onCompare: () -> Unit,
    onKeepNote: () -> Unit,
    onUseCopy: () -> Unit,
    modifier: Modifier = Modifier,
    /** Whose version the note holds: the server's, or the folder's when the notes are a folder. */
    keptSide: String = "server",
) {
    val (original, copy, viewingCopy, moreCopies) = conflict
    val side = keptSideName(keptSide)
    val copyName = copy.substringAfterLast('/')
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.SyncProblem, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (viewingCopy) {
                        stringResource(Res.string.notes_conflict_copy_title, original)
                    } else {
                        stringResource(Res.string.notes_conflict_note_title)
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    viewingCopy -> stringResource(Res.string.notes_conflict_copy_text, side)
                    moreCopies > 0 -> pluralStringResource(Res.plurals.notes_conflict_note_text_more, moreCopies, side, copyName, moreCopies)
                    else -> stringResource(Res.string.notes_conflict_note_text, side, copyName)
                },
                style = MaterialTheme.typography.bodySmall,
            )
            FlowRow(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onCompare) { Text(stringResource(Res.string.notes_conflict_compare)) }
                TextButton(onClick = if (viewingCopy) onUseCopy else onKeepNote) {
                    Text(stringResource(if (viewingCopy) Res.string.notes_conflict_use_this else Res.string.notes_conflict_keep_this))
                }
                TextButton(onClick = if (viewingCopy) onKeepNote else onUseCopy) {
                    Text(stringResource(if (viewingCopy) Res.string.notes_conflict_discard_this else Res.string.notes_conflict_use_other))
                }
            }
        }
    }
}

/**
 * The note and its copy, change by change, to settle chunk by chunk as `git add -p` does: each
 * chunk under the heading it falls in, lines only in the note marked −, lines only in the copy
 * marked +, and a choice of the note's, the copy's, both, or lines written in their place. What a
 * choice leaves out is struck through. Applying needs a choice for every chunk; see
 * [com.korgnote.common.sync.resolveConflict].
 *
 * The choices last while the two texts do: a sync that changes either brings new chunks, and
 * choices made on the old ones would land on other lines.
 */
@Composable
fun ConflictCompareDialog(
    comparison: ConflictComparison,
    onApply: (List<ChunkChoice>) -> Unit,
    onDismiss: () -> Unit,
    /** As in [ConflictBanner]. */
    keptSide: String = "server",
    /** Why the last apply was refused, if it was. */
    error: String? = null,
) {
    val chunks = comparison.chunks
    val choices = remember(comparison) { mutableStateListOf<ChunkChoice?>().apply { repeat(chunks.size) { add(null) } } }
    val left = choices.count { it == null }
    val colors = diffColors()
    // Not an AlertDialog: that one stays where it is when the keyboard opens, and an edited chunk's
    // keyboard then covered the field and Apply both. This card is laid out in what the keyboard
    // and the system bars leave, the chunks shrinking to fit, so the buttons stay in view. A tap
    // beside it does nothing, rather than throwing away the choices made so far.
    Dialog(
        onDismissRequest = onDismiss,
        properties = cardDialogProperties(),
    ) {
        Box(
            Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
            ) {
                Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 12.dp)) {
                    Text(stringResource(Res.string.notes_conflict_compare_title), style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(12.dp))
                    LegendText(
                        stringResource(Res.string.notes_conflict_only_in_note, comparison.original.substringAfterLast('/'), keptSideName(keptSide)),
                        colors.note,
                    )
                    Spacer(Modifier.height(2.dp))
                    LegendText(stringResource(Res.string.notes_conflict_only_in_copy, comparison.copy.substringAfterLast('/')), colors.copy)
                    Spacer(Modifier.height(8.dp))
                    if (chunks.isEmpty()) {
                        Text(stringResource(Res.string.notes_conflict_same), style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text(stringResource(Res.string.notes_conflict_resolve_hint), style = MaterialTheme.typography.bodySmall)
                        FlowRow {
                            TextButton(onClick = { choices.indices.forEach { choices[it] = ChunkChoice.Note } }) {
                                Text(stringResource(Res.string.notes_conflict_all_note))
                            }
                            TextButton(onClick = { choices.indices.forEach { choices[it] = ChunkChoice.Copy } }) {
                                Text(stringResource(Res.string.notes_conflict_all_copy))
                            }
                        }
                    }
                    error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    LazyColumn(Modifier.weight(1f, fill = false)) {
                        itemsIndexed(chunks) { index, chunk ->
                            ChunkChooser(chunk, choices[index], colors, onChoose = { choices[index] = it })
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) {
                        Text(
                            if (left > 0) pluralStringResource(Res.plurals.notes_conflict_left, left, left) else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onDismiss) { Text(stringResource(Res.string.close)) }
                        TextButton(onClick = { onApply(choices.map { it!! }) }, enabled = left == 0) {
                            Text(stringResource(Res.string.notes_conflict_apply))
                        }
                    }
                }
            }
        }
    }
}

/**
 * The backgrounds of the note's lines and the copy's: red and green, as in any diff, light or dark
 * with the theme. The theme's own containers will not do: in Material's defaults the error and
 * tertiary ones are both a pale pink, and on a phone the two sides could not be told apart.
 */
private data class DiffColors(val note: Color, val copy: Color)

@Composable
private fun diffColors(): DiffColors =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) {
        DiffColors(note = Color(0xFF5E2A2A), copy = Color(0xFF214A2E))
    } else {
        DiffColors(note = Color(0xFFFFDCDC), copy = Color(0xFFD3F2D9))
    }

/** A line of the legend, on the background its side's lines have. */
@Composable
private fun LegendText(text: String, background: Color) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.background(background, MaterialTheme.shapes.extraSmall).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** One chunk of [ConflictCompareDialog]: where it is, both sides, and what to keep of it. */
@Composable
private fun ChunkChooser(chunk: ConflictChunk, choice: ChunkChoice?, colors: DiffColors, onChoose: (ChunkChoice) -> Unit) {
    val keepsNote = choice == null || choice == ChunkChoice.Note || choice == ChunkChoice.Both
    val keepsCopy = choice == null || choice == ChunkChoice.Copy || choice == ChunkChoice.Both
    Column(Modifier.padding(top = 12.dp)) {
        Text(
            chunk.heading ?: stringResource(Res.string.notes_conflict_before_first_heading),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(bottom = 2.dp),
        )
        for (line in chunk.before) DiffText("  $line", background = null, kept = true)
        for (line in chunk.note) DiffText("− $line", colors.note, kept = keepsNote)
        for (line in chunk.copy) DiffText("+ $line", colors.copy, kept = keepsCopy)
        for (line in chunk.after) DiffText("  $line", background = null, kept = true)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ChoiceChip(Res.string.notes_conflict_take_note, choice == ChunkChoice.Note) { onChoose(ChunkChoice.Note) }
            ChoiceChip(Res.string.notes_conflict_take_copy, choice == ChunkChoice.Copy) { onChoose(ChunkChoice.Copy) }
            ChoiceChip(Res.string.notes_conflict_take_both, choice == ChunkChoice.Both) { onChoose(ChunkChoice.Both) }
            ChoiceChip(Res.string.notes_conflict_take_edit, choice is ChunkChoice.Edited) {
                // Starts from what was chosen so far, or from both sides when nothing was.
                if (choice !is ChunkChoice.Edited) {
                    onChoose(ChunkChoice.Edited((choice ?: ChunkChoice.Both).lines(chunk).joinToString("\n")))
                }
            }
        }
        if (choice is ChunkChoice.Edited) {
            OutlinedTextField(
                value = choice.text,
                onValueChange = { onChoose(ChunkChoice.Edited(it)) },
                label = { Text(stringResource(Res.string.notes_conflict_edit_label)) },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ChoiceChip(label: StringResource, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(stringResource(label)) })
}

/** One line of a chunk; struck through and faded when the choice leaves it out. */
@Composable
private fun DiffText(text: String, background: Color?, kept: Boolean) {
    Text(
        text,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall,
        textDecoration = if (kept) null else TextDecoration.LineThrough,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (background != null) Modifier.background(background) else Modifier)
            .alpha(if (kept) 1f else 0.45f)
            .padding(horizontal = 4.dp),
    )
}

/** `server` or `folder`, as [ConflictBanner]'s `keptSide` names them, in the app's language. */
@Composable
private fun keptSideName(keptSide: String): String =
    stringResource(if (keptSide == "folder") Res.string.notes_conflict_side_folder else Res.string.notes_conflict_side_server)
