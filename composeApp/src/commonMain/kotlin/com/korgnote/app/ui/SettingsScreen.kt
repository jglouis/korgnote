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
import com.korgnote.app.files.FileLinkMapping
import com.korgnote.app.notifications.ReminderSettings
import com.korgnote.common.fs.NotePaths
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.author
import korgnote.composeapp.generated.resources.cancel
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
import korgnote.composeapp.generated.resources.settings_error_daily_folder
import korgnote.composeapp.generated.resources.settings_error_folder
import korgnote.composeapp.generated.resources.settings_error_host_port
import korgnote.composeapp.generated.resources.settings_error_minutes
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
@OptIn(ExperimentalMaterial3Api::class)
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
) {
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf("") }
    var portText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<StringResource?>(null) }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var remindersOn by remember { mutableStateOf(true) }
    var leadText by remember { mutableStateOf("") }
    var digestOn by remember { mutableStateOf(false) }
    var digestTime by remember { mutableStateOf(ReminderSettings().digestTime) }
    var pickingDigestTime by remember { mutableStateOf(false) }
    var newNotesFolder by remember { mutableStateOf("") }
    var fileLinks by remember { mutableStateOf(emptyList<FileLinkMapping>()) }
    var author by remember { mutableStateOf("") }
    var dailyFolder by remember { mutableStateOf("") }
    var extraHeader by remember { mutableStateOf("") }
    var tagColors by remember { mutableStateOf(emptyMap<String, Int>()) }
    // What the form held when loaded or last saved; null until loaded.
    val form = listOf(
        host, portText, remindersOn, leadText, digestOn, digestTime, newNotesFolder, fileLinks,
        author, dailyFolder, extraHeader, tagColors,
    )
    var stored by remember { mutableStateOf<List<Any>?>(null) }
    val changed = stored != null && form != stored

    LaunchedEffect(Unit) {
        host = repo.getServerHost()
        portText = repo.getServerPort().toString()
        val reminders = repo.getReminderSettings()
        remindersOn = reminders.enabled
        leadText = reminders.leadMinutes.toString()
        digestOn = reminders.digestEnabled
        digestTime = reminders.digestTime
        newNotesFolder = repo.getNewNotesFolder()
        fileLinks = repo.getFileLinkMappings()
        val template = repo.getNoteTemplate()
        author = template.author
        dailyFolder = template.dailyFolder
        extraHeader = template.extraHeader
        tagColors = repo.getTagColors()
        stored = listOf(
            host, portText, remindersOn, leadText, digestOn, digestTime, newNotesFolder, fileLinks,
            author, dailyFolder, extraHeader, tagColors,
        )
    }

    if (pickingDigestTime) {
        val state = rememberTimePickerState(initialHour = digestTime.hour, initialMinute = digestTime.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickingDigestTime = false },
            title = { Text(stringResource(Res.string.settings_digest_at)) },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    digestTime = LocalTime(state.hour, state.minute)
                    pickingDigestTime = false
                }) { Text(stringResource(Res.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { pickingDigestTime = false }) { Text(stringResource(Res.string.cancel)) } },
        )
    }

    fun save() {
        val port = portText.toIntOrNull()
        if (usesLocalServer && (host.isBlank() || port == null || port !in 1..65535)) {
            error = Res.string.settings_error_host_port
            return
        }
        val lead = leadText.toIntOrNull()
        if (remindersOn && (lead == null || lead !in 0..1440)) {
            error = Res.string.settings_error_minutes
            return
        }
        val folder = newNotesFolder.trim().trim('/')
        // Checked as a note path would be, so a bad folder is refused here and not by the server.
        if (folder.isNotEmpty() && NotePaths.normalize("$folder/x.org") == null) {
            error = Res.string.settings_error_folder
            return
        }
        val daily = dailyFolder.trim().trim('/')
        if (daily.isNotEmpty() && NotePaths.normalize("$daily/x.org") == null) {
            error = Res.string.settings_error_daily_folder
            return
        }
        fileLinkMappingsError(fileLinks)?.let {
            error = it
            return
        }
        error = null
        scope.launch {
            saving = true
            try {
                if (usesLocalServer && port != null) {
                    repo.setServerHost(host.trim())
                    repo.setServerPort(port)
                }
                val current = repo.getReminderSettings()
                repo.setReminderSettings(
                    ReminderSettings(
                        enabled = remindersOn,
                        leadMinutes = lead ?: current.leadMinutes,
                        digestEnabled = digestOn,
                        digestTime = digestTime,
                    ),
                )
                repo.setNewNotesFolder(folder)
                repo.setNoteTemplate(NoteTemplateSettings(author.trim(), daily, extraHeader.trimEnd()))
                repo.setTagColors(tagColors)
                TagColors.chosen = tagColors
                repo.setFileLinkMappings(
                    fileLinks.filter { it.from.isNotBlank() }.map { FileLinkMapping(it.from.trim(), it.to.trim()) },
                )
                stored = form
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
                if (onChangeNotesSource != null) {
                    SettingsSection(
                        icon = if (notesSource is NotesSource.Folder) Icons.Filled.Folder else Icons.Filled.Sync,
                        title = stringResource(Res.string.settings_notes),
                        subtitle = when (notesSource) {
                            is NotesSource.Folder -> stringResource(Res.string.settings_notes_in_folder, notesFolderLabel(notesSource.location))
                            NotesSource.Server -> stringResource(Res.string.settings_notes_on_server)
                            NotesSource.WebDav -> repo.getWebDavAccount()
                                ?.let { stringResource(Res.string.settings_notes_on_webdav_account, it.url, it.username) }
                                ?: stringResource(Res.string.settings_notes_on_webdav)
                        },
                        trailing = { if (!onServer) serverStatus() },
                    ) {
                        if (notesSource == NotesSource.WebDav) syncStatus()
                        OutlinedButton(onClick = onChangeNotesSource) { Text(stringResource(Res.string.settings_change_notes_source)) }
                    }
                }
                // Whether the server can be reached, and what conflicts syncing left, next to its
                // settings: there is no bar above them.
                if (onServer && (canPair || usesLocalServer)) {
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
                                onChanged = { onSaved?.invoke() },
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
                                value = host,
                                onValueChange = { host = it },
                                label = { Text(stringResource(Res.string.settings_host)) },
                                singleLine = true,
                            )
                            OutlinedTextField(
                                modifier = Modifier.fillMaxWidth(),
                                value = portText,
                                onValueChange = { portText = it.filter { ch -> ch.isDigit() } },
                                label = { Text(stringResource(Res.string.settings_port)) },
                                singleLine = true,
                            )
                        }
                    }
                } else if (onServer) {
                    // Nothing to set, but somewhere to see the server's state.
                    SettingsSection(
                        icon = Icons.Filled.Computer,
                        title = stringResource(Res.string.settings_server),
                        subtitle = stringResource(Res.string.settings_server_subtitle),
                        trailing = serverStatus,
                    ) {
                        syncStatus()
                    }
                }

                SettingsSection(
                    icon = Icons.Filled.Notifications,
                    title = stringResource(Res.string.settings_reminders),
                    subtitle = stringResource(Res.string.settings_reminders_subtitle),
                ) {
                    // Turned down on the welcome screens, or never asked: this is where to change one's mind.
                    val notifications = rememberNotificationPermission()
                    if (notifications != null && !notifications.granted && (remindersOn || digestOn)) {
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
                    SwitchRow(stringResource(Res.string.settings_remind_before), remindersOn) { remindersOn = it }
                    if (remindersOn) {
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = leadText,
                            onValueChange = { leadText = it.filter { ch -> ch.isDigit() }.take(4) },
                            label = { Text(stringResource(Res.string.settings_minutes_before)) },
                            singleLine = true,
                        )
                    }
                    SwitchRow(stringResource(Res.string.settings_digest), digestOn) { digestOn = it }
                    if (digestOn) {
                        OutlinedButton(onClick = { pickingDigestTime = true }) {
                            Icon(Icons.Filled.Schedule, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                            Text(stringResource(Res.string.settings_at_time, digestTime.hhmm()))
                        }
                    }
                }

                SettingsSection(
                    icon = Icons.Filled.EditNote,
                    title = stringResource(Res.string.settings_new_notes),
                    subtitle = stringResource(Res.string.settings_new_notes_subtitle),
                ) {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = newNotesFolder,
                        onValueChange = { newNotesFolder = it },
                        label = { Text(stringResource(Res.string.settings_new_notes_folder)) },
                        placeholder = { Text("inbox") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = dailyFolder,
                        onValueChange = { dailyFolder = it },
                        label = { Text(stringResource(Res.string.settings_dailies_folder)) },
                        placeholder = { Text(NoteTemplateSettings.DEFAULT_DAILY_FOLDER) },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = author,
                        onValueChange = { author = it },
                        label = { Text(stringResource(Res.string.author)) },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = extraHeader,
                        onValueChange = { extraHeader = it },
                        label = { Text(stringResource(Res.string.settings_extra_header)) },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        minLines = 2,
                    )
                    Hint(stringResource(Res.string.settings_header_note))
                }

                SettingsSection(
                    icon = Icons.Filled.Palette,
                    title = stringResource(Res.string.settings_tag_colours),
                    subtitle = stringResource(Res.string.settings_tag_colours_subtitle),
                ) {
                    TagColorSettings(knownTags, tagColors) { tagColors = it }
                }

                SettingsSection(
                    icon = Icons.Filled.Link,
                    title = stringResource(Res.string.settings_file_links),
                    subtitle = stringResource(Res.string.settings_file_links_subtitle),
                ) {
                    FileLinkSettings(fileLinks) { fileLinks = it }
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
