package com.korgnote.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.korgnote.app.capture.CaptureFiler
import com.korgnote.app.capture.CaptureRequest
import com.korgnote.app.capture.DeviceCalendar
import com.korgnote.common.org.DEFAULT_TODO_KEYWORDS
import com.korgnote.common.org.OrgTodoConfig
import com.korgnote.common.org.capture.Captured
import com.korgnote.common.org.capture.EntryParts
import com.korgnote.common.org.capture.HeadingAdditions
import com.korgnote.common.org.capture.HeadingRef
import com.korgnote.common.org.capture.SharedContent
import com.korgnote.common.org.capture.available
import com.korgnote.common.org.capture.captureInto
import com.korgnote.common.org.capture.entryKeyword
import com.korgnote.common.org.capture.icsOf
import com.korgnote.common.org.capture.withEntryKeyword
import com.korgnote.common.org.link.LinkTarget
import com.korgnote.common.org.toOrgDocument
import com.korgnote.common.replica.DocumentStore
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.cancel
import korgnote.composeapp.generated.resources.capture_add_link
import korgnote.composeapp.generated.resources.capture_add_location
import korgnote.composeapp.generated.resources.capture_add_text
import korgnote.composeapp.generated.resources.capture_add_time
import korgnote.composeapp.generated.resources.capture_add_to_it
import korgnote.composeapp.generated.resources.capture_capture
import korgnote.composeapp.generated.resources.capture_destination
import korgnote.composeapp.generated.resources.capture_new_entry_under
import korgnote.composeapp.generated.resources.capture_new_note
import korgnote.composeapp.generated.resources.capture_new_note_named
import korgnote.composeapp.generated.resources.capture_no_keyword
import korgnote.composeapp.generated.resources.capture_nothing_to_add
import korgnote.composeapp.generated.resources.capture_reading_notes
import korgnote.composeapp.generated.resources.capture_save_to
import korgnote.composeapp.generated.resources.capture_where_to
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Where a capture goes, once the user chose: a note, made for it if new, or a heading. */
private sealed interface Destination {
    data class Note(val path: String, val title: String, val isNew: Boolean = false) : Destination
    data class Heading(val target: LinkTarget) : Destination
}

/**
 * Capturing, with as little in the way as there can be: where it goes, and what it is.
 *
 * - **Destination**: the inbox until another note, a heading, or a new note is chosen from a search
 *   of the notes. In a heading, the capture is added to it — a place as its location, an event as
 *   its `SCHEDULED:` — or filed under it as a sub-entry.
 * - **Capture**: the entry, shown as the note shows Org, and edited, on a tap, as the note edits an
 *   element. A new entry can be marked with one of the destination's TODO keywords.
 *
 * Saving hands [onSave] the capture to queue; nothing is written here.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun CaptureSheet(
    shared: SharedContent,
    store: DocumentStore,
    filer: CaptureFiler,
    onSave: (CaptureRequest) -> Unit,
    onDismiss: () -> Unit,
    /** Every note and heading a capture can go to: the app's own when it has them at hand. */
    destinations: suspend () -> List<LinkTarget> = { filer.destinations(store) },
    /** The phone's calendar, for capturing one of its events; null where there is none to read. */
    calendar: DeviceCalendar? = null,
    /** Opens on the calendar's events: how the app captures, and the launcher's Event shortcut. */
    startWithCalendar: Boolean = false,
) {
    // Stamped once, when the sheet opens: the entry says when it was captured, not when it was saved.
    var request by remember(shared) { mutableStateOf(filer.request(shared)) }
    val captured: Captured = filer.captured(request)
    var destination by remember { mutableStateOf<Destination?>(null) }
    var addToHeading by remember { mutableStateOf(true) }
    // What to add to a heading: what fits what was shared, until a box is ticked or unticked by hand.
    var ticked by remember(request.shared) { mutableStateOf<HeadingAdditions?>(null) }
    val additions = ticked ?: HeadingAdditions.defaultsFor(captured)

    // For a place: its location, its link, or both, in a new entry; the location alone to start with.
    val parts = remember(request) { EntryParts.defaultsFor(captured) }
    var withLocation by remember(request) { mutableStateOf(parts.location) }
    var withLink by remember(request) { mutableStateOf(parts.link) }
    var keyword by remember(request) { mutableStateOf<String?>(null) }

    // The destination's keywords, for the chips and for showing the entry as that note would.
    val path = when (val chosen = destination) {
        is Destination.Note -> chosen.path
        is Destination.Heading -> chosen.target.path
        null -> filer.inbox()
    }
    var keywords by remember { mutableStateOf<OrgTodoConfig>(DEFAULT_TODO_KEYWORDS) }
    LaunchedEffect(path) { keywords = filer.keywordsFor(store, path) }

    // The entry, as made from what was shared, then as the keyword chips and the editor change it.
    val made = remember(request, withLocation, withLink) { filer.render(request, withLocation, withLink) }
    var entry by remember(made) { mutableStateOf(withEntryKeyword(made.text, keyword, keywords.todo)) }
    // Open in the editor: at once for a capture of nothing, which has only its heading to type.
    var editing by remember(made) {
        mutableStateOf(if (request.shared == SharedContent()) TextFieldValue(made.text, TextRange(made.cursor)) else null)
    }
    val text = editing?.text ?: entry

    var allDestinations by remember { mutableStateOf<List<LinkTarget>?>(null) }
    var picking by remember { mutableStateOf(false) }
    val calendarPermission = calendar?.let { rememberCalendarPermission() }
    var pickingEvent by remember { mutableStateOf(startWithCalendar && calendarPermission != null) }

    if (picking) {
        val loaded = allDestinations
        if (loaded == null) {
            LaunchedEffect(Unit) { allDestinations = destinations() }
            AlertDialog(
                onDismissRequest = { picking = false },
                text = { Text(stringResource(Res.string.capture_reading_notes)) },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(Res.string.cancel)) } },
            )
        } else {
            LinkPickerDialog(
                targets = loaded,
                currentPath = path,
                onPick = { target ->
                    destination = if (target.isNote) Destination.Note(target.path, target.title) else Destination.Heading(target)
                    picking = false
                },
                onTypeLink = null,
                onDismiss = { picking = false },
                title = stringResource(Res.string.capture_where_to),
                onNew = { name ->
                    filer.newNotePath(name)?.let { destination = Destination.Note(it, name, isNew = true) }
                    picking = false
                },
                newLabel = { stringResource(Res.string.capture_new_note_named, it) },
            )
        }
        return
    }
    if (pickingEvent && calendar != null && calendarPermission != null) {
        CalendarEventPicker(
            calendar = calendar,
            permission = calendarPermission,
            onPick = { event ->
                request = filer.request(SharedContent(calendar = icsOf(event)))
                pickingEvent = false
            },
            onDismiss = {
                pickingEvent = false
                // Opened for the calendar alone: with no event picked, there is nothing to capture.
                if (startWithCalendar && request.shared == SharedContent()) onDismiss()
            },
        )
        return
    }

    val heading = (destination as? Destination.Heading)?.target
    val adding = heading != null && addToHeading
    // In a heading, what it becomes: its line, with what is added under it.
    val added = heading?.takeIf { adding }?.let { previewOfAdditions(it, captured, additions) }
    val where = when (val chosen = destination) {
        is Destination.Note -> if (chosen.isNew) stringResource(Res.string.capture_new_note, chosen.path) else chosen.path
        is Destination.Heading -> "${chosen.target.where} › ${chosen.target.title}"
        null -> path
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SectionLabel(Res.string.capture_destination)
                OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) {
                    val icon = when (val chosen = destination) {
                        is Destination.Heading -> Icons.AutoMirrored.Filled.Notes
                        is Destination.Note -> if (chosen.isNew) Icons.AutoMirrored.Filled.NoteAdd else Icons.Outlined.Description
                        null -> Icons.Filled.Inbox
                    }
                    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(where, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
                if (heading != null) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        SegmentedButton(
                            selected = addToHeading,
                            onClick = { addToHeading = true },
                            shape = SegmentedButtonDefaults.itemShape(0, 2),
                        ) { Text(stringResource(Res.string.capture_add_to_it), maxLines = 1) }
                        SegmentedButton(
                            selected = !addToHeading,
                            onClick = { addToHeading = false },
                            shape = SegmentedButtonDefaults.itemShape(1, 2),
                        ) { Text(stringResource(Res.string.capture_new_entry_under), maxLines = 1) }
                    }
                }
                if (adding) {
                    val can = HeadingAdditions.available(captured)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        if (can.location) AdditionChip(Icons.Filled.Place, Res.string.capture_add_location, additions.location) { ticked = additions.copy(location = it) }
                        if (can.link) AdditionChip(Icons.Filled.Link, Res.string.capture_add_link, additions.link) { ticked = additions.copy(link = it) }
                        if (can.time) AdditionChip(Icons.Filled.Schedule, Res.string.capture_add_time, additions.time) { ticked = additions.copy(time = it) }
                        if (can.text) AdditionChip(Icons.AutoMirrored.Filled.Notes, Res.string.capture_add_text, additions.text) { ticked = additions.copy(text = it) }
                    }
                } else {
                    val can = EntryParts.available(captured)
                    // A place: its location, its link, or both. Changing them makes the entry again.
                    if (can.location && can.link) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                            AdditionChip(Icons.Filled.Place, Res.string.capture_add_location, withLocation) { withLocation = it }
                            AdditionChip(Icons.Filled.Link, Res.string.capture_add_link, withLink) { withLink = it }
                        }
                    }
                }
                if (!adding && keywords.todo.isNotEmpty()) {
                    // The other choice a new entry offers: whether it is something to do, and which.
                    val current = entryKeyword(text, keywords.todo)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        for (choice in listOf<String?>(null) + keywords.todo) {
                            FilterChip(
                                selected = choice == current,
                                onClick = {
                                    val marked = withEntryKeyword(text, choice, keywords.todo)
                                    keyword = choice
                                    entry = marked
                                    editing = editing?.let { TextFieldValue(marked, TextRange(marked.indexOf('\n').takeIf { it >= 0 } ?: marked.length)) }
                                },
                                label = { Text(choice ?: stringResource(Res.string.capture_no_keyword)) },
                            )
                        }
                    }
                }

                SectionLabel(Res.string.capture_capture)
                val open = editing
                when {
                    adding -> if (added == null) Hint(stringResource(Res.string.capture_nothing_to_add)) else OrgShown(added, keywords, onClick = null)
                    open != null -> Box(Modifier.fillMaxWidth().orgSurface()) {
                        OrgElementEditor(
                            elementKey = 0,
                            value = open,
                            onValueChange = { editing = it },
                            onCompletionKey = { false },
                            todoKeywords = keywords,
                        )
                    }
                    else -> OrgShown(text, keywords, onClick = { editing = TextFieldValue(text, TextRange(text.indexOf('\n').takeIf { it >= 0 } ?: text.length)) })
                }
            }
        },
        confirmButton = {
            val ready = if (adding) added != null else text.isNotBlank()
            TextButton(
                enabled = ready,
                onClick = {
                    val chosen = destination
                    onSave(
                        when {
                            chosen is Destination.Heading && addToHeading ->
                                request.copy(into = HeadingRef.of(chosen.target), additions = additions)
                            chosen is Destination.Heading ->
                                request.copy(under = HeadingRef.of(chosen.target), entry = text)
                            chosen is Destination.Note ->
                                request.copy(file = chosen.path, fileTitle = chosen.title.takeIf { chosen.isNew }, entry = text)
                            else -> request.copy(entry = text)
                        },
                    )
                },
            ) { Text(stringResource(Res.string.capture_save_to, shortWhere(destination, path))) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}

/** The destination as the Save button says it: the heading's title, or the note's file. */
private fun shortWhere(destination: Destination?, path: String): String = when (destination) {
    is Destination.Heading -> destination.target.title
    else -> path.substringAfterLast('/')
}

@Composable
private fun SectionLabel(label: StringResource) {
    Text(
        stringResource(label),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
    )
}

@Composable
private fun AdditionChip(icon: ImageVector, label: StringResource, selected: Boolean, onChange: (Boolean) -> Unit) {
    FilterChip(
        selected = selected,
        onClick = { onChange(!selected) },
        label = { Text(stringResource(label)) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
    )
}

/** [text] as the note shows Org, with [keywords] for its headings; a tap edits it when [onClick] says so. */
@Composable
private fun OrgShown(text: String, keywords: OrgTodoConfig, onClick: (() -> Unit)?) {
    val document = remember(text, keywords) { text.toOrgDocument(keywords) }
    Box(Modifier.fillMaxWidth().orgSurface().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)) {
        OrgText(content = document, todoKeywords = keywords)
    }
}

@Composable
private fun Modifier.orgSurface(): Modifier =
    background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 8.dp)

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
}

/** [heading]'s line with what [additions] add under it: what the heading becomes, as far as the capture goes. */
private fun previewOfAdditions(heading: LinkTarget, captured: Captured, additions: HeadingAdditions): String? {
    val line = "*".repeat(heading.outline.size + 1) + " " + heading.title + "\n"
    return captureInto(line, HeadingRef("", title = heading.title), captured, additions)?.takeIf { it != line }
}
