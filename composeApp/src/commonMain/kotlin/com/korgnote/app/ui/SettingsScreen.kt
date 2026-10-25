package com.korgnote.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.korgnote.app.data.SettingsRepository
import com.korgnote.app.files.FileLinkMapping
import com.korgnote.app.notifications.ReminderSettings
import com.korgnote.common.fs.NotePaths
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.ui.Alignment
import com.korgnote.app.di.buildBaseUrl
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun SettingsScreen(repo: SettingsRepository, onSaved: (() -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf("") }
    var portText by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var remindersOn by remember { mutableStateOf(true) }
    var leadText by remember { mutableStateOf("") }
    var digestOn by remember { mutableStateOf(false) }
    var digestText by remember { mutableStateOf("") }
    var newNotesFolder by remember { mutableStateOf("") }
    var fileLinks by remember { mutableStateOf(emptyList<FileLinkMapping>()) }

    LaunchedEffect(Unit) {
        host = repo.getServerHost()
        portText = repo.getServerPort().toString()
        token = repo.getServerToken()
        val reminders = repo.getReminderSettings()
        remindersOn = reminders.enabled
        leadText = reminders.leadMinutes.toString()
        digestOn = reminders.digestEnabled
        digestText = reminders.digestTime.hhmm()
        newNotesFolder = repo.getNewNotesFolder()
        fileLinks = repo.getFileLinkMappings()
    }

    Column {
        Text("Server configuration")
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = host,
            onValueChange = { host = it },
            label = { Text("Server host (e.g. 192.168.1.10 or http://example.com)") },
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
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = token,
            onValueChange = { token = it },
            label = { Text("Access token (leave empty if the server does not require one)") },
            singleLine = true,
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
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = digestText,
                onValueChange = { digestText = it.filter { ch -> ch.isDigit() || ch == ':' }.take(5) },
                label = { Text("At (HH:MM)") },
                singleLine = true,
            )
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

        Spacer(Modifier.height(24.dp))
        FileLinkSettings(fileLinks) { fileLinks = it }

        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(error!!)
        }
        Spacer(Modifier.height(12.dp))
        Button(enabled = !saving, onClick = {
            val port = portText.toIntOrNull()
            if (host.isBlank() || port == null || port !in 1..65535) {
                error = "Please enter a valid host and port (1-65535)."
                return@Button
            }
            val lead = leadText.toIntOrNull()
            if (remindersOn && (lead == null || lead !in 0..1440)) {
                error = "Please enter how many minutes before (0-1440)."
                return@Button
            }
            val digestTime = ReminderSettings.parseTime(digestText)
            if (digestOn && digestTime == null) {
                error = "Please enter the digest time as HH:MM."
                return@Button
            }
            val folder = newNotesFolder.trim().trim('/')
            // Checked as a note path would be, so a bad folder is refused here and not by the server.
            if (folder.isNotEmpty() && NotePaths.normalize("$folder/x.org") == null) {
                error = "Please enter a folder inside the notes, like inbox or notes/inbox."
                return@Button
            }
            fileLinkMappingsError(fileLinks)?.let {
                error = it
                return@Button
            }
            error = null
            scope.launch {
                saving = true
                try {
                    repo.setServerHost(host.trim())
                    repo.setServerPort(port)
                    repo.setServerToken(token.trim())
                    val current = repo.getReminderSettings()
                    repo.setReminderSettings(
                        ReminderSettings(
                            enabled = remindersOn,
                            leadMinutes = lead ?: current.leadMinutes,
                            digestEnabled = digestOn,
                            digestTime = digestTime ?: current.digestTime,
                        ),
                    )
                    repo.setNewNotesFolder(folder)
                    repo.setFileLinkMappings(
                        fileLinks.filter { it.from.isNotBlank() }.map { FileLinkMapping(it.from.trim(), it.to.trim()) },
                    )
                    saved = true
                    onSaved?.invoke()
                } finally {
                    // show saved feedback briefly
                    kotlinx.coroutines.delay(1200.milliseconds)
                    saved = false
                    saving = false
                }
            }
        }) {
            Text("Save")
        }
        Spacer(Modifier.height(8.dp))
        val preview = run {
            val p = portText.toIntOrNull() ?: 0
            if (host.isNotBlank() && p in 1..65535) buildBaseUrl(host, p) else ""
        }
        if (preview.isNotBlank()) {
            Text("Base URL: $preview")
        }
        if (saved) {
            Spacer(Modifier.height(4.dp))
            Text("Saved. Reloading…")
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
