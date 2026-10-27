package com.korgnote.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.korgnote.app.data.DevicePairing
import com.korgnote.app.data.PairingLinks
import com.korgnote.app.data.NoteTemplateSettings
import com.korgnote.app.data.SettingsRepository
import com.korgnote.app.files.FileLinkMapping
import com.korgnote.app.notifications.ReminderSettings
import com.korgnote.common.fs.NotePaths
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalTime
import kotlin.time.Duration.Companion.milliseconds

/**
 * The app's settings, saved together. The save button shows only once something has changed, and
 * stays at the bottom of the screen however far down the change was.
 *
 * Scrolls itself, so that the button can stay put: the screen hosting it must not scroll.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    repo: SettingsRepository,
    modifier: Modifier = Modifier,
    serverStatus: @Composable () -> Unit = {},
    onSaved: (() -> Unit)? = null,
    /** Tags used across the notes, most used first: the ones a colour can be chosen for. */
    knownTags: List<String> = emptyList(),
    /** Pairs this device with a computer; null where nothing can, as in tests. */
    pairing: DevicePairing? = null,
) {
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf("") }
    var portText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
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
            title = { Text("Morning digest at") },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    digestTime = LocalTime(state.hour, state.minute)
                    pickingDigestTime = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickingDigestTime = false }) { Text("Cancel") } },
        )
    }

    fun save() {
        val port = portText.toIntOrNull()
        if (host.isBlank() || port == null || port !in 1..65535) {
            error = "Please enter a valid host and port (1-65535)."
            return
        }
        val lead = leadText.toIntOrNull()
        if (remindersOn && (lead == null || lead !in 0..1440)) {
            error = "Please enter how many minutes before (0-1440)."
            return
        }
        val folder = newNotesFolder.trim().trim('/')
        // Checked as a note path would be, so a bad folder is refused here and not by the server.
        if (folder.isNotEmpty() && NotePaths.normalize("$folder/x.org") == null) {
            error = "Please enter a folder inside the notes, like inbox or notes/inbox."
            return
        }
        val daily = dailyFolder.trim().trim('/')
        if (daily.isNotEmpty() && NotePaths.normalize("$daily/x.org") == null) {
            error = "Please enter a folder inside the notes for dailies, like daily."
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
                repo.setServerHost(host.trim())
                repo.setServerPort(port)
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
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            // Whether the server can be reached, and what conflicts syncing left, next to its
            // settings: there is no bar above them.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Server", modifier = Modifier.weight(1f))
                serverStatus()
            }
            Spacer(Modifier.height(8.dp))
            val incomingLink by PairingLinks.pending.collectAsState()
            PairingSettings(
                repo,
                pairing,
                onChanged = { onSaved?.invoke() },
                incomingLink = incomingLink,
                onIncomingLinkUsed = { PairingLinks.pending.value = null },
            )

            Spacer(Modifier.height(24.dp))
            Text("Server on this computer")
            Text(
                "Used when not paired: by the desktop app and the web client on the computer the server runs on.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = host,
                onValueChange = { host = it },
                label = { Text("Host (localhost, or 10.0.2.2 from the Android emulator)") },
                singleLine = true
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = portText,
                onValueChange = { portText = it.filter { ch -> ch.isDigit() } },
                label = { Text("Port") },
                singleLine = true
            )

            Spacer(Modifier.height(24.dp))
            Text("Reminders")
            Text(
                "Notifications on this device, scheduled from the agenda. They work offline.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(8.dp))
            LabelledSwitch("Remind me before timed entries", remindersOn) { remindersOn = it }
            if (remindersOn) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = leadText,
                    onValueChange = { leadText = it.filter { ch -> ch.isDigit() }.take(4) },
                    label = { Text("Minutes before") },
                    singleLine = true,
                )
            }
            Spacer(Modifier.height(8.dp))
            LabelledSwitch("Morning digest of the day's agenda", digestOn) { digestOn = it }
            if (digestOn) {
                OutlinedButton(onClick = { pickingDigestTime = true }) {
                    Icon(Icons.Filled.Schedule, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text("At ${digestTime.hhmm()}")
                }
            }

            Spacer(Modifier.height(24.dp))
            Text("New notes")
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = newNotesFolder,
                onValueChange = { newNotesFolder = it },
                label = { Text("Folder for new notes (empty for the top)") },
                placeholder = { Text("inbox") },
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = dailyFolder,
                onValueChange = { dailyFolder = it },
                label = { Text("Folder for dailies (empty for the top)") },
                placeholder = { Text(NoteTemplateSettings.DEFAULT_DAILY_FOLDER) },
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = author,
                onValueChange = { author = it },
                label = { Text("Author") },
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = extraHeader,
                onValueChange = { extraHeader = it },
                label = { Text("Extra header lines, such as #+TODO:") },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                minLines = 2,
            )
            Text(
                "Each new note and daily also gets its own :ID:, #+TITLE:, #+DATE: and your tags.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )

            Spacer(Modifier.height(24.dp))
            TagColorSettings(knownTags, tagColors) { tagColors = it }

            Spacer(Modifier.height(24.dp))
            FileLinkSettings(fileLinks) { fileLinks = it }
            // Room to scroll the last field out from under the button.
            Spacer(Modifier.height(96.dp))
        }
        Column(
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            error?.let {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                    Text(it, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(12.dp))
                }
            }
            AnimatedVisibility(visible = changed || saved, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                ExtendedFloatingActionButton(
                    onClick = { if (!saving) save() },
                    icon = { Icon(if (saved) Icons.Filled.Check else Icons.Filled.Save, contentDescription = null) },
                    text = { Text(if (saved) "Saved" else "Save") },
                )
            }
        }
    }
}

@Composable
private fun LabelledSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
