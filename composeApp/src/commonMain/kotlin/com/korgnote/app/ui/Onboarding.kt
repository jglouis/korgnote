package com.korgnote.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.korgnote.app.data.DevicePairing
import com.korgnote.app.data.NotesSource
import com.korgnote.app.data.PairingLinks
import com.korgnote.app.data.SettingsRepository
import com.korgnote.app.data.WebDavAccount
import com.korgnote.app.data.WebDavCheck
import com.korgnote.app.data.WebDavSyncApi
import com.korgnote.app.di.WEBDAV
import com.korgnote.app.files.NoteFolderAccess
import com.korgnote.app.files.notesFolderLabel
import com.korgnote.app.files.notesFolderPickingHint
import com.korgnote.app.files.notesFolderPlaces
import com.korgnote.app.files.rememberNotesFolderPicker
import com.korgnote.common.replica.ReplicaStorage
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.back
import korgnote.composeapp.generated.resources.notes_onboarding_against
import korgnote.composeapp.generated.resources.notes_onboarding_all_offline
import korgnote.composeapp.generated.resources.notes_onboarding_allow_local_network
import korgnote.composeapp.generated.resources.notes_onboarding_allow_notifications
import korgnote.composeapp.generated.resources.notes_onboarding_app_password
import korgnote.composeapp.generated.resources.notes_onboarding_both_offline
import korgnote.composeapp.generated.resources.notes_onboarding_check_explained
import korgnote.composeapp.generated.resources.notes_onboarding_check_server
import korgnote.composeapp.generated.resources.notes_onboarding_checking_server
import korgnote.composeapp.generated.resources.notes_onboarding_choose_another
import korgnote.composeapp.generated.resources.notes_onboarding_choose_folder
import korgnote.composeapp.generated.resources.notes_onboarding_choose_folder_title
import korgnote.composeapp.generated.resources.notes_onboarding_continue
import korgnote.composeapp.generated.resources.notes_onboarding_folder_address
import korgnote.composeapp.generated.resources.notes_onboarding_folder_con
import korgnote.composeapp.generated.resources.notes_onboarding_folder_count
import korgnote.composeapp.generated.resources.notes_onboarding_folder_empty
import korgnote.composeapp.generated.resources.notes_onboarding_folder_pro
import korgnote.composeapp.generated.resources.notes_onboarding_folder_title
import korgnote.composeapp.generated.resources.notes_onboarding_folder_unreadable
import korgnote.composeapp.generated.resources.notes_onboarding_folder_unusable
import korgnote.composeapp.generated.resources.notes_onboarding_for
import korgnote.composeapp.generated.resources.notes_onboarding_https_needed
import korgnote.composeapp.generated.resources.notes_onboarding_install_server
import korgnote.composeapp.generated.resources.notes_onboarding_intro
import korgnote.composeapp.generated.resources.notes_onboarding_local_network_computer
import korgnote.composeapp.generated.resources.notes_onboarding_local_network_server
import korgnote.composeapp.generated.resources.notes_onboarding_looking
import korgnote.composeapp.generated.resources.notes_onboarding_merged
import korgnote.composeapp.generated.resources.notes_onboarding_nextcloud_address
import korgnote.composeapp.generated.resources.notes_onboarding_no_folder
import korgnote.composeapp.generated.resources.notes_onboarding_not_now
import korgnote.composeapp.generated.resources.notes_onboarding_pair_later
import korgnote.composeapp.generated.resources.notes_onboarding_password
import korgnote.composeapp.generated.resources.notes_onboarding_reminders
import korgnote.composeapp.generated.resources.notes_onboarding_reminders_intro
import korgnote.composeapp.generated.resources.notes_onboarding_reminders_later
import korgnote.composeapp.generated.resources.notes_onboarding_server_con
import korgnote.composeapp.generated.resources.notes_onboarding_server_count
import korgnote.composeapp.generated.resources.notes_onboarding_server_empty
import korgnote.composeapp.generated.resources.notes_onboarding_server_heading
import korgnote.composeapp.generated.resources.notes_onboarding_server_pro
import korgnote.composeapp.generated.resources.notes_onboarding_server_step1
import korgnote.composeapp.generated.resources.notes_onboarding_server_step2
import korgnote.composeapp.generated.resources.notes_onboarding_server_step3
import korgnote.composeapp.generated.resources.notes_onboarding_server_title
import korgnote.composeapp.generated.resources.notes_onboarding_server_unusable
import korgnote.composeapp.generated.resources.notes_onboarding_unpaired
import korgnote.composeapp.generated.resources.notes_onboarding_use_folder
import korgnote.composeapp.generated.resources.notes_onboarding_use_server
import korgnote.composeapp.generated.resources.notes_onboarding_user_name
import korgnote.composeapp.generated.resources.notes_onboarding_webdav_con
import korgnote.composeapp.generated.resources.notes_onboarding_webdav_heading
import korgnote.composeapp.generated.resources.notes_onboarding_webdav_intro
import korgnote.composeapp.generated.resources.notes_onboarding_webdav_pro
import korgnote.composeapp.generated.resources.notes_onboarding_webdav_title
import korgnote.composeapp.generated.resources.notes_onboarding_welcome
import korgnote.composeapp.generated.resources.notes_onboarding_where
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.mp.KoinPlatform

/**
 * The welcome screens: where the notes are, and the permissions that choice needs, one step at a
 * time. Shown on the first start, and again from Settings to keep the notes somewhere else.
 *
 * The choices are set side by side with what each costs, since the README is not where anyone
 * starts: a folder another app syncs, the KorgNote server on the computer with the notes, or, where
 * it is offered, a WebDAV server (experimental).
 */
@Composable
fun Onboarding(
    /** Where the notes are now, when Settings opened this; null on the first start. */
    current: NotesSource?,
    onChosen: (NotesSource) -> Unit,
    /** Goes back to the notes as they were; null on the first start, when there is nothing to go back to. */
    onCancel: (() -> Unit)?,
) {
    val repo = koinInject<SettingsRepository>()
    val pairing = remember { KoinPlatform.getKoin().getOrNull<DevicePairing>() }
    val folders = remember { KoinPlatform.getKoin().getOrNull<NoteFolderAccess>() }
    // Offered where the platform gives the WebDAV sync a replica of its own.
    val webDav = remember {
        KoinPlatform.getKoin().run { getOrNull<ReplicaStorage>(WEBDAV)?.let { get<WebDavSyncApi>(WEBDAV) } }
    }
    val notifications = rememberNotificationPermission()
    var step by rememberSaveable { mutableStateOf(OnboardingStep.CHOOSE) }
    // Chosen, waiting for the last step: kept encoded, so that it survives a rotation.
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }

    // Notifications are asked about once, on the first start; from Settings the choice is all.
    fun finish(source: NotesSource) {
        if (current == null && notifications != null && !notifications.granted) {
            chosen = source.encode()
            step = OnboardingStep.REMINDERS
        } else {
            onChosen(source)
        }
    }

    fun back() {
        step = when (step) {
            OnboardingStep.CHOOSE -> {
                onCancel?.invoke()
                return
            }
            OnboardingStep.FOLDER, OnboardingStep.SERVER, OnboardingStep.WEBDAV -> OnboardingStep.CHOOSE
            OnboardingStep.REMINDERS -> when (chosen?.let(NotesSource::decode)) {
                is NotesSource.Folder -> OnboardingStep.FOLDER
                NotesSource.WebDav -> OnboardingStep.WEBDAV
                else -> OnboardingStep.SERVER
            }
        }
    }

    // A pairing link opened from the camera is for the server's step, whatever step is showing.
    val incomingLink by PairingLinks.pending.collectAsState()
    LaunchedEffect(incomingLink) {
        if (incomingLink != null) step = OnboardingStep.SERVER
    }

    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = step != OnboardingStep.CHOOSE || onCancel != null,
        onBackCompleted = ::back,
    )

    Surface(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (step != OnboardingStep.CHOOSE || onCancel != null) {
                    IconButton(onClick = ::back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.back))
                    }
                } else {
                    Spacer(Modifier.height(32.dp))
                }
                when (step) {
                    OnboardingStep.CHOOSE -> ChooseStep(
                        changing = current != null,
                        onFolder = { step = OnboardingStep.FOLDER },
                        onServer = { step = OnboardingStep.SERVER },
                        onWebDav = webDav?.let { { step = OnboardingStep.WEBDAV } },
                    )
                    OnboardingStep.FOLDER -> FolderStep(
                        initial = (current as? NotesSource.Folder)?.location,
                        folders = folders,
                        onUse = { finish(NotesSource.Folder(it)) },
                    )
                    OnboardingStep.SERVER -> ServerStep(
                        repo = repo,
                        pairing = pairing,
                        incomingLink = incomingLink,
                        onDone = { finish(NotesSource.Server) },
                    )
                    OnboardingStep.WEBDAV -> webDav?.let { api ->
                        WebDavStep(repo = repo, api = api, onDone = { finish(NotesSource.WebDav) })
                    }
                    OnboardingStep.REMINDERS -> RemindersStep(
                        permission = notifications,
                        onDone = { chosen?.let(NotesSource::decode)?.let(onChosen) },
                    )
                }
            }
        }
    }
}

internal enum class OnboardingStep { CHOOSE, FOLDER, SERVER, WEBDAV, REMINDERS }

@Composable
private fun ChooseStep(changing: Boolean, onFolder: () -> Unit, onServer: () -> Unit, onWebDav: (() -> Unit)? = null) {
    Text(
        stringResource(if (changing) Res.string.notes_onboarding_where else Res.string.notes_onboarding_welcome),
        style = MaterialTheme.typography.headlineSmall,
    )
    Text(stringResource(Res.string.notes_onboarding_intro))
    SourceOption(
        icon = Icons.Filled.Folder,
        title = stringResource(Res.string.notes_onboarding_folder_title),
        pros = listOf(stringResource(Res.string.notes_onboarding_folder_pro), notesFolderPlaces),
        cons = listOf(stringResource(Res.string.notes_onboarding_folder_con)),
        onClick = onFolder,
    )
    SourceOption(
        icon = Icons.Filled.Computer,
        title = stringResource(Res.string.notes_onboarding_server_title),
        pros = listOf(stringResource(Res.string.notes_onboarding_server_pro), stringResource(Res.string.notes_onboarding_merged)),
        cons = listOf(stringResource(Res.string.notes_onboarding_server_con)),
        onClick = onServer,
    )
    if (onWebDav != null) {
        SourceOption(
            icon = Icons.Filled.Cloud,
            title = stringResource(Res.string.notes_onboarding_webdav_title),
            pros = listOf(
                stringResource(Res.string.notes_onboarding_webdav_pro),
                stringResource(Res.string.notes_onboarding_merged),
            ),
            cons = listOf(stringResource(Res.string.notes_onboarding_webdav_con)),
            onClick = onWebDav,
        )
    }
    Hint(
        stringResource(
            if (onWebDav != null) Res.string.notes_onboarding_all_offline else Res.string.notes_onboarding_both_offline,
        )
    )
}

@Composable
private fun SourceOption(icon: ImageVector, title: String, pros: List<String>, cons: List<String>, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            pros.forEach { Point(Icons.Filled.Check, it, good = true) }
            cons.forEach { Point(Icons.Filled.Remove, it, good = false) }
        }
    }
}

@Composable
private fun Point(icon: ImageVector, text: String, good: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            icon,
            contentDescription = stringResource(if (good) Res.string.notes_onboarding_for else Res.string.notes_onboarding_against),
            tint = if (good) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(18.dp),
        )
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** What a folder just picked holds, as checked before it is used. */
private sealed interface FolderCheck {
    data object Checking : FolderCheck
    data class Notes(val count: Int) : FolderCheck
    data class Unusable(val reason: String) : FolderCheck
}

@Composable
private fun FolderStep(initial: String?, folders: NoteFolderAccess?, onUse: (String) -> Unit) {
    var picked by rememberSaveable { mutableStateOf(initial) }
    var check by remember { mutableStateOf<FolderCheck>(FolderCheck.Checking) }
    val pick = rememberNotesFolderPicker { picked = it }
    LaunchedEffect(picked) {
        val location = picked ?: return@LaunchedEffect
        check = FolderCheck.Checking
        check = try {
            FolderCheck.Notes(folders?.open(location)?.list()?.size ?: 0)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FolderCheck.Unusable(e.message ?: getString(Res.string.notes_onboarding_folder_unreadable))
        }
    }

    Text(stringResource(Res.string.notes_onboarding_choose_folder_title), style = MaterialTheme.typography.headlineSmall)
    Text(notesFolderPickingHint)
    val location = picked
    if (location != null) {
        OutlinedCard(Modifier.fillMaxWidth()) {
            Row(
                Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column {
                    Text(notesFolderLabel(location), style = MaterialTheme.typography.titleSmall)
                    val status = check
                    Text(
                        when (status) {
                            FolderCheck.Checking -> stringResource(Res.string.notes_onboarding_looking)
                            is FolderCheck.Notes -> when (status.count) {
                                0 -> stringResource(Res.string.notes_onboarding_folder_empty)
                                else -> pluralStringResource(Res.plurals.notes_onboarding_folder_count, status.count, status.count)
                            }
                            is FolderCheck.Unusable -> stringResource(Res.string.notes_onboarding_folder_unusable, status.reason)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (status is FolderCheck.Unusable) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (location == null) {
            if (pick != null) Button(onClick = pick) { Text(stringResource(Res.string.notes_onboarding_choose_folder)) }
        } else {
            Button(onClick = { onUse(location) }, enabled = check is FolderCheck.Notes) {
                Text(stringResource(Res.string.notes_onboarding_use_folder))
            }
            if (pick != null) OutlinedButton(onClick = pick) { Text(stringResource(Res.string.notes_onboarding_choose_another)) }
        }
    }
    if (pick == null) Hint(stringResource(Res.string.notes_onboarding_no_folder))
}

@Composable
private fun ServerStep(repo: SettingsRepository, pairing: DevicePairing?, incomingLink: String?, onDone: () -> Unit) {
    var paired by remember { mutableStateOf(repo.getPairedServer() != null) }
    val localNetwork = rememberLocalNetworkPermission()
    val uriHandler = LocalUriHandler.current

    Text(stringResource(Res.string.notes_onboarding_server_heading), style = MaterialTheme.typography.headlineSmall)
    if (!paired) {
        Numbered(1, stringResource(Res.string.notes_onboarding_server_step1))
        TextButton(onClick = { uriHandler.openUri(INSTALL_URL) }) { Text(stringResource(Res.string.notes_onboarding_install_server)) }
        Numbered(2, stringResource(Res.string.notes_onboarding_server_step2, DEVICES_URL))
        Numbered(3, stringResource(Res.string.notes_onboarding_server_step3))
    }
    if (localNetwork != null && !localNetwork.granted) {
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Filled.Wifi, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(Res.string.notes_onboarding_local_network_computer))
                }
                Button(onClick = { localNetwork.request { } }) { Text(stringResource(Res.string.notes_onboarding_allow_local_network)) }
            }
        }
    }
    PairingSettings(
        repo,
        pairing,
        onChanged = { paired = repo.getPairedServer() != null },
        incomingLink = incomingLink,
        onIncomingLinkUsed = { PairingLinks.pending.value = null },
        showHowTo = false,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onDone, enabled = paired) { Text(stringResource(Res.string.notes_onboarding_continue)) }
        if (!paired) TextButton(onClick = onDone) { Text(stringResource(Res.string.notes_onboarding_pair_later)) }
    }
    if (!paired) Hint(stringResource(Res.string.notes_onboarding_unpaired))
}

/**
 * Where the WebDAV folder is and how to sign in there, checked before anything is saved: a server
 * that would let two devices overwrite each other's edits is refused here, with why.
 */
@Composable
private fun WebDavStep(repo: SettingsRepository, api: WebDavSyncApi, onDone: () -> Unit) {
    val saved = remember { repo.getWebDavAccount() }
    var url by rememberSaveable { mutableStateOf(saved?.url.orEmpty()) }
    var username by rememberSaveable { mutableStateOf(saved?.username.orEmpty()) }
    var password by rememberSaveable { mutableStateOf(saved?.password.orEmpty()) }
    // What the check found for the account as typed, forgotten as soon as any of it changes.
    var checked by remember { mutableStateOf<Pair<WebDavAccount, WebDavCheck>?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val localNetwork = rememberLocalNetworkPermission()
    val account = WebDavAccount(url.trim(), username.trim(), password)
    val result = checked?.takeIf { it.first == account }?.second
    val secure = account.url.startsWith("https://", ignoreCase = true)

    Text(stringResource(Res.string.notes_onboarding_webdav_heading), style = MaterialTheme.typography.headlineSmall)
    Text(stringResource(Res.string.notes_onboarding_webdav_intro))
    OutlinedTextField(
        value = url,
        onValueChange = { url = it },
        label = { Text(stringResource(Res.string.notes_onboarding_folder_address)) },
        placeholder = { Text("https://cloud.example.com/remote.php/dav/files/you/notes") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Hint(stringResource(Res.string.notes_onboarding_nextcloud_address))
    OutlinedTextField(
        value = username,
        onValueChange = { username = it },
        label = { Text(stringResource(Res.string.notes_onboarding_user_name)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text(stringResource(Res.string.notes_onboarding_password)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    Hint(stringResource(Res.string.notes_onboarding_app_password))
    if (localNetwork != null && !localNetwork.granted) {
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Filled.Wifi, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(Res.string.notes_onboarding_local_network_server))
                }
                Button(onClick = { localNetwork.request { } }) { Text(stringResource(Res.string.notes_onboarding_allow_local_network)) }
            }
        }
    }
    if (account.url.isNotEmpty() && !secure) {
        Text(
            stringResource(Res.string.notes_onboarding_https_needed),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    when (result) {
        null -> if (checking) Text(stringResource(Res.string.notes_onboarding_checking_server), style = MaterialTheme.typography.bodySmall)
        is WebDavCheck.Usable -> Text(
            when (result.notes) {
                0 -> stringResource(Res.string.notes_onboarding_server_empty)
                else -> pluralStringResource(Res.plurals.notes_onboarding_server_count, result.notes, result.notes)
            },
            style = MaterialTheme.typography.bodySmall,
        )
        is WebDavCheck.Unusable -> Text(
            stringResource(Res.string.notes_onboarding_server_unusable, result.reason),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (result is WebDavCheck.Usable) {
            Button(onClick = {
                repo.setWebDavAccount(account)
                onDone()
            }) { Text(stringResource(Res.string.notes_onboarding_use_server)) }
        } else {
            Button(
                onClick = {
                    val trying = account
                    checking = true
                    scope.launch {
                        try {
                            checked = trying to api.trying(trying).check()
                        } finally {
                            checking = false
                        }
                    }
                },
                enabled = secure && !checking,
            ) { Text(stringResource(Res.string.notes_onboarding_check_server)) }
        }
    }
    Hint(stringResource(Res.string.notes_onboarding_check_explained))
}

@Composable
private fun Numbered(number: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Text("$number.", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text(text)
    }
}

@Composable
private fun RemindersStep(permission: PermissionAsk?, onDone: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Filled.NotificationsActive, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(Res.string.notes_onboarding_reminders), style = MaterialTheme.typography.headlineSmall)
    }
    Text(stringResource(Res.string.notes_onboarding_reminders_intro))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { if (permission == null) onDone() else permission.request { onDone() } }) {
            Text(stringResource(Res.string.notes_onboarding_allow_notifications))
        }
        TextButton(onClick = onDone) { Text(stringResource(Res.string.notes_onboarding_not_now)) }
    }
    Hint(stringResource(Res.string.notes_onboarding_reminders_later))
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
}

private const val INSTALL_URL = "https://github.com/jglouis/korgnote#install"
private const val DEVICES_URL = "http://localhost:8080/devices"
