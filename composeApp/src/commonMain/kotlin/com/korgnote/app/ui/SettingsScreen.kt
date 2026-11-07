package com.korgnote.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.korgnote.app.data.DevicePairing
import com.korgnote.app.data.PairingLinks
import com.korgnote.app.data.canPair
import com.korgnote.app.data.usesLocalServer
import com.korgnote.app.data.NoteTemplateSettings
import com.korgnote.app.data.NotesSource
import com.korgnote.app.files.notesFolderLabel
import com.korgnote.app.data.SettingsRepository
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.author
import korgnote.composeapp.generated.resources.ok
import korgnote.composeapp.generated.resources.save
import korgnote.composeapp.generated.resources.saved
import korgnote.composeapp.generated.resources.settings
import korgnote.composeapp.generated.resources.settings_allow_notifications
import korgnote.composeapp.generated.resources.settings_at_time
import korgnote.composeapp.generated.resources.settings_change_notes_source
import korgnote.composeapp.generated.resources.settings_digest
import korgnote.composeapp.generated.resources.settings_digest_at
import korgnote.composeapp.generated.resources.settings_dailies_folder
import korgnote.composeapp.generated.resources.settings_extra_header
import korgnote.composeapp.generated.resources.settings_file_links
import korgnote.composeapp.generated.resources.settings_file_links_subtitle
import korgnote.composeapp.generated.resources.settings_header_note
import korgnote.composeapp.generated.resources.settings_host
import korgnote.composeapp.generated.resources.settings_local_server
import korgnote.composeapp.generated.resources.settings_local_server_this_page
import korgnote.composeapp.generated.resources.settings_local_server_when_unpaired
import korgnote.composeapp.generated.resources.settings_minutes_before
import korgnote.composeapp.generated.resources.settings_new_notes
import korgnote.composeapp.generated.resources.settings_new_notes_folder
import korgnote.composeapp.generated.resources.settings_new_notes_subtitle
import korgnote.composeapp.generated.resources.settings_notes
import korgnote.composeapp.generated.resources.settings_notes_in_folder
import korgnote.composeapp.generated.resources.settings_notes_on_server
import korgnote.composeapp.generated.resources.settings_notes_on_webdav
import korgnote.composeapp.generated.resources.settings_notes_on_webdav_account
import korgnote.composeapp.generated.resources.settings_notifications_blocked
import korgnote.composeapp.generated.resources.settings_port
import korgnote.composeapp.generated.resources.settings_remind_before
import korgnote.composeapp.generated.resources.settings_reminders
import korgnote.composeapp.generated.resources.settings_reminders_subtitle
import korgnote.composeapp.generated.resources.settings_server
import korgnote.composeapp.generated.resources.settings_server_subtitle
import korgnote.composeapp.generated.resources.settings_tag_colours
import korgnote.composeapp.generated.resources.settings_tag_colours_subtitle
import korgnote.composeapp.generated.resources.settings_tour
import korgnote.composeapp.generated.resources.settings_tour_open
import korgnote.composeapp.generated.resources.settings_tour_subtitle
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalTime
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.milliseconds

/**
 * The app's settings, in sections, saved together. The save button shows only once something has
 * changed, and stays at the bottom of the screen however far down the change was.
 *
 * Scrolls itself, so that the button can stay put: the screen hosting it must not scroll.
 */
@Composable
fun SettingsScreen(
    repo: SettingsRepository,
    modifier: Modifier = Modifier,
    serverStatus: @Composable () -> Unit = {},
    /** The same state written out, with when the notes last synced, under the server's title. */
    syncStatus: @Composable () -> Unit = {},
    onSaved: (() -> Unit)? = null,
    /** Tags used across the notes, most used first: the ones a colour can be chosen for. */
    knownTags: List<String> = emptyList(),
    /** Pairs this device with a computer; null where nothing can, as in tests. */
    pairing: DevicePairing? = null,
    /** Where the notes are: pairing is only for the server. */
    notesSource: NotesSource = NotesSource.Server,
    /** Opens the welcome screens again, to keep the notes elsewhere; null where there is no choice. */
    onChangeNotesSource: (() -> Unit)? = null,
    /** Notifications were allowed from here, so the reminders already planned can be shown. */
    onNotificationsAllowed: () -> Unit = {},
    /** Opens the tour note, adding it to the notes when it is not there; null to leave it out. */
    onTour: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var form by remember { mutableStateOf(SettingsForm()) }
    // What the form held when loaded or last saved; null until loaded.
    var stored by remember { mutableStateOf<SettingsForm?>(null) }
    val changed = stored != null && form != stored
    var error by remember { mutableStateOf<StringResource?>(null) }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        form = SettingsForm.load(repo)
        stored = form
    }

    fun save() {
        val toSave = form
        error = toSave.validate()
        if (error != null) return
        scope.launch {
            saving = true
            try {
                toSave.save(repo)
                stored = toSave
                saved = true
                onSaved?.invoke()
            } finally {
                // "Saved" shows briefly in place of the button.
                kotlinx.coroutines.delay(1200.milliseconds)
                saved = false
                saving = false
            }
        }
    }

    Box(modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // A column of cards reads badly when stretched across a desktop window.
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    stringResource(Res.string.settings),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                )

                // The server's settings and pairing are only for the server; a folder or a WebDAV
                // server shows its state beside where the notes are.
                val onServer = notesSource == NotesSource.Server
                if (onChangeNotesSource != null) NotesSourceSection(repo, notesSource, onChangeNotesSource, serverStatus, syncStatus)
                if (onServer) ServerSection(form, { form = it }, repo, pairing, serverStatus, syncStatus, onPaired = { onSaved?.invoke() })
                RemindersSection(form, { form = it }, onNotificationsAllowed)
                NewNotesSection(form) { form = it }

                SettingsSection(
                    icon = Icons.Filled.Palette,
                    title = stringResource(Res.string.settings_tag_colours),
                    subtitle = stringResource(Res.string.settings_tag_colours_subtitle),
                ) {
                    TagColorSettings(knownTags, form.tagColors) { form = form.copy(tagColors = it) }
                }

                SettingsSection(
                    icon = Icons.Filled.Link,
                    title = stringResource(Res.string.settings_file_links),
                    subtitle = stringResource(Res.string.settings_file_links_subtitle),
                ) {
                    FileLinkSettings(form.fileLinks) { form = form.copy(fileLinks = it) }
                }

                if (onTour != null) {
                    SettingsSection(
                        icon = Icons.Filled.TouchApp,
                        title = stringResource(Res.string.settings_tour),
                        subtitle = stringResource(Res.string.settings_tour_subtitle),
                    ) {
                        OutlinedButton(onClick = onTour) { Text(stringResource(Res.string.settings_tour_open)) }
                    }
                }
                // Room to scroll the last field out from under the button.
                Spacer(Modifier.height(80.dp))
            }
        }
        Column(
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            error?.let {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                    Text(stringResource(it), color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(12.dp))
                }
            }
            AnimatedVisibility(visible = changed || saved, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                ExtendedFloatingActionButton(
                    onClick = { if (!saving) save() },
                    icon = { Icon(if (saved) Icons.Filled.Check else Icons.Filled.Save, contentDescription = null) },
                    text = { Text(stringResource(if (saved) Res.string.saved else Res.string.save)) },
                )
            }
        }
    }
}

/** Where the notes are kept, with the way to keep them elsewhere. A folder or a WebDAV server shows its state here. */
@Composable
private fun NotesSourceSection(
    repo: SettingsRepository,
    notesSource: NotesSource,
    onChangeNotesSource: () -> Unit,
    serverStatus: @Composable () -> Unit,
    syncStatus: @Composable () -> Unit,
) {
    // Changing the account goes through the welcome screens, which compose all this afresh.
    val webDavAccount = remember(repo, notesSource) { if (notesSource == NotesSource.WebDav) repo.getWebDavAccount() else null }
    SettingsSection(
        icon = if (notesSource is NotesSource.Folder) Icons.Filled.Folder else Icons.Filled.Sync,
        title = stringResource(Res.string.settings_notes),
        subtitle = when (notesSource) {
            is NotesSource.Folder -> stringResource(Res.string.settings_notes_in_folder, notesFolderLabel(notesSource.location))
            NotesSource.Server -> stringResource(Res.string.settings_notes_on_server)
            NotesSource.WebDav -> webDavAccount
                ?.let { stringResource(Res.string.settings_notes_on_webdav_account, it.url, it.username) }
                ?: stringResource(Res.string.settings_notes_on_webdav)
        },
        trailing = { if (notesSource != NotesSource.Server) serverStatus() },
    ) {
        if (notesSource == NotesSource.WebDav) syncStatus()
        OutlinedButton(onClick = onChangeNotesSource) { Text(stringResource(Res.string.settings_change_notes_source)) }
    }
}

/**
 * Whether the server can be reached, and what conflicts syncing left, next to its settings: there is
 * no bar above them. Where there is nothing to set, it is still somewhere to see the server's state.
 */
@Composable
private fun ServerSection(
    form: SettingsForm,
    onChange: (SettingsForm) -> Unit,
    repo: SettingsRepository,
    pairing: DevicePairing?,
    serverStatus: @Composable () -> Unit,
    syncStatus: @Composable () -> Unit,
    onPaired: () -> Unit,
) {
    SettingsSection(
        icon = Icons.Filled.Computer,
        title = stringResource(Res.string.settings_server),
        subtitle = stringResource(Res.string.settings_server_subtitle),
        trailing = serverStatus,
    ) {
        syncStatus()
        if (canPair) {
            val incomingLink by PairingLinks.pending.collectAsState()
            PairingSettings(
                repo,
                pairing,
                onChanged = onPaired,
                incomingLink = incomingLink,
                onIncomingLinkUsed = { PairingLinks.pending.value = null },
            )
        }
        // Only where there is a computer of this device's own to run a server: not on a phone.
        if (usesLocalServer) {
            if (canPair) SectionDivider()
            Text(stringResource(Res.string.settings_local_server), style = MaterialTheme.typography.titleSmall)
            Hint(
                stringResource(
                    if (canPair) Res.string.settings_local_server_when_unpaired else Res.string.settings_local_server_this_page,
                ),
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = form.host,
                onValueChange = { onChange(form.copy(host = it)) },
                label = { Text(stringResource(Res.string.settings_host)) },
                singleLine = true,
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = form.portText,
                onValueChange = { onChange(form.copy(portText = it.filter { ch -> ch.isDigit() })) },
                label = { Text(stringResource(Res.string.settings_port)) },
                singleLine = true,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemindersSection(form: SettingsForm, onChange: (SettingsForm) -> Unit, onNotificationsAllowed: () -> Unit) {
    var pickingDigestTime by remember { mutableStateOf(false) }
    if (pickingDigestTime) {
        val state = rememberTimePickerState(initialHour = form.digestTime.hour, initialMinute = form.digestTime.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickingDigestTime = false },
            title = { Text(stringResource(Res.string.settings_digest_at)) },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    onChange(form.copy(digestTime = LocalTime(state.hour, state.minute)))
                    pickingDigestTime = false
                }) { Text(stringResource(Res.string.ok)) }
            },
            dismissButton = { CancelButton(onClick = { pickingDigestTime = false }) },
        )
    }
    SettingsSection(
        icon = Icons.Filled.Notifications,
        title = stringResource(Res.string.settings_reminders),
        subtitle = stringResource(Res.string.settings_reminders_subtitle),
    ) {
        // Turned down on the welcome screens, or never asked: this is where to change one's mind.
        val notifications = rememberNotificationPermission()
        if (notifications != null && !notifications.granted && (form.remindersOn || form.digestOn)) {
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(Res.string.settings_notifications_blocked),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    OutlinedButton(onClick = { notifications.request { granted -> if (granted) onNotificationsAllowed() } }) {
                        Text(stringResource(Res.string.settings_allow_notifications))
                    }
                }
            }
        }
        SwitchRow(stringResource(Res.string.settings_remind_before), form.remindersOn) { onChange(form.copy(remindersOn = it)) }
        if (form.remindersOn) {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = form.leadText,
                onValueChange = { onChange(form.copy(leadText = it.filter { ch -> ch.isDigit() }.take(4))) },
                label = { Text(stringResource(Res.string.settings_minutes_before)) },
                singleLine = true,
            )
        }
        SwitchRow(stringResource(Res.string.settings_digest), form.digestOn) { onChange(form.copy(digestOn = it)) }
        if (form.digestOn) {
            OutlinedButton(onClick = { pickingDigestTime = true }) {
                Icon(Icons.Filled.Schedule, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text(stringResource(Res.string.settings_at_time, form.digestTime.hhmm()))
            }
        }
    }
}

@Composable
private fun NewNotesSection(form: SettingsForm, onChange: (SettingsForm) -> Unit) {
    SettingsSection(
        icon = Icons.Filled.EditNote,
        title = stringResource(Res.string.settings_new_notes),
        subtitle = stringResource(Res.string.settings_new_notes_subtitle),
    ) {
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = form.newNotesFolder,
            onValueChange = { onChange(form.copy(newNotesFolder = it)) },
            label = { Text(stringResource(Res.string.settings_new_notes_folder)) },
            placeholder = { Text("inbox") },
            singleLine = true,
        )
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = form.dailyFolder,
            onValueChange = { onChange(form.copy(dailyFolder = it)) },
            label = { Text(stringResource(Res.string.settings_dailies_folder)) },
            placeholder = { Text(NoteTemplateSettings.DEFAULT_DAILY_FOLDER) },
            singleLine = true,
        )
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = form.author,
            onValueChange = { onChange(form.copy(author = it)) },
            label = { Text(stringResource(Res.string.author)) },
            singleLine = true,
        )
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = form.extraHeader,
            onValueChange = { onChange(form.copy(extraHeader = it)) },
            label = { Text(stringResource(Res.string.settings_extra_header)) },
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            minLines = 2,
        )
        Hint(stringResource(Res.string.settings_header_note))
    }
}

/**
 * One section of Settings: a card with an icon, a title and what the section is for, then its
 * fields. [trailing] sits at the title's end, for a state such as the server's.
 */
@Composable
private fun SettingsSection(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    trailing: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape, modifier = Modifier.size(40.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    subtitle?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                trailing()
            }
            content()
        }
    }
}

/** A setting turned on and off: the whole row toggles it, not only the switch. */
@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f).padding(end = 16.dp), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** A line under a setting saying more about it. */
@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SectionDivider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
