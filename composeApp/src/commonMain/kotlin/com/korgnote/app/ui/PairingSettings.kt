package com.korgnote.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.korgnote.app.data.DevicePairing
import com.korgnote.app.data.PairingResult
import com.korgnote.app.data.SettingsRepository
import com.korgnote.common.pairing.PairingLink
import com.korgnote.common.pairing.PairingLinkCheck
import korgnote.composeapp.generated.resources.Res
import korgnote.composeapp.generated.resources.settings_forget
import korgnote.composeapp.generated.resources.settings_forget_text
import korgnote.composeapp.generated.resources.settings_forget_title
import korgnote.composeapp.generated.resources.settings_pair
import korgnote.composeapp.generated.resources.settings_pair_how_to
import korgnote.composeapp.generated.resources.settings_pair_not_a_link
import korgnote.composeapp.generated.resources.ok
import korgnote.composeapp.generated.resources.settings_pair_failed_title
import korgnote.composeapp.generated.resources.settings_pair_incomplete
import korgnote.composeapp.generated.resources.settings_pair_no_address
import korgnote.composeapp.generated.resources.settings_pair_title
import korgnote.composeapp.generated.resources.settings_paired_as
import korgnote.composeapp.generated.resources.settings_paired_with
import korgnote.composeapp.generated.resources.settings_pairing_with
import korgnote.composeapp.generated.resources.settings_paste_link
import korgnote.composeapp.generated.resources.settings_reached_at
import korgnote.composeapp.generated.resources.settings_scan_again
import korgnote.composeapp.generated.resources.settings_scan_code
import korgnote.composeapp.generated.resources.settings_the_computer
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * Pairing this device with the computer its notes are on: scanning the QR code from the server's
 * Devices page, or pasting the link under it. Once paired, the app syncs with that computer over
 * TLS, at whichever of its addresses answers, trusting its certificate and no other.
 */
@Composable
fun PairingSettings(
    repo: SettingsRepository,
    pairing: DevicePairing?,
    /** After pairing or forgetting, so that syncing starts again against what is now set. */
    onChanged: () -> Unit,
    /** A link that arrived from outside — another app opened one — to pair with at once. */
    incomingLink: String? = null,
    onIncomingLinkUsed: () -> Unit = {},
    /** Says where the code is shown; off where the screen around it already says so. */
    showHowTo: Boolean = true,
    /** Its own heading; off where a section around it already has one. */
    showTitle: Boolean = true,
) {
    val scope = rememberCoroutineScope()
    var paired by remember { mutableStateOf(repo.getPairedServer()) }
    var pasted by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    /** Why the last try did not pair, with what to do: a dialog, as pairing is not to fail unnoticed. */
    var failure by remember { mutableStateOf<String?>(null) }
    var forgetting by remember { mutableStateOf(false) }
    val deviceName = rememberDeviceName()

    fun pairWith(text: String) = scope.launch {
        message = null
        val link = when (val check = PairingLink.check(text)) {
            is PairingLinkCheck.Valid -> check.link
            PairingLinkCheck.NotALink -> {
                val shown = text.trim().let { if (it.length > 80) it.take(80) + "…" else it }
                failure = getString(Res.string.settings_pair_not_a_link, shown)
                return@launch
            }
            is PairingLinkCheck.NoAddress -> {
                failure = getString(Res.string.settings_pair_no_address, check.serverName.ifBlank { getString(Res.string.settings_the_computer) })
                return@launch
            }
            PairingLinkCheck.Incomplete -> {
                failure = getString(Res.string.settings_pair_incomplete)
                return@launch
            }
        }
        if (pairing == null) return@launch
        busy = true
        message = getString(Res.string.settings_pairing_with, link.serverName.ifBlank { getString(Res.string.settings_the_computer) })
        when (val result = pairing.pair(link, deviceName)) {
            is PairingResult.Paired -> {
                paired = result.server
                pasted = ""
                onChanged()
                message = getString(Res.string.settings_paired_with, result.server.serverName)
            }
            is PairingResult.Failed -> {
                message = null
                failure = result.reason
            }
        }
        busy = false
    }

    LaunchedEffect(incomingLink) {
        incomingLink?.let {
            onIncomingLinkUsed()
            pairWith(it)
        }
    }
    val scan = rememberPairingScanner { pairWith(it) }

    failure?.let { reason ->
        AlertDialog(
            onDismissRequest = { failure = null },
            title = { Text(stringResource(Res.string.settings_pair_failed_title)) },
            text = { Text(reason) },
            confirmButton = {
                if (scan != null && pairing != null) {
                    TextButton(onClick = { failure = null; scan() }) { Text(stringResource(Res.string.settings_scan_again)) }
                }
            },
            dismissButton = { TextButton(onClick = { failure = null }) { Text(stringResource(Res.string.ok)) } },
        )
    }

    if (forgetting) {
        AlertDialog(
            onDismissRequest = { forgetting = false },
            title = { Text(stringResource(Res.string.settings_forget_title, paired?.serverName ?: stringResource(Res.string.settings_the_computer))) },
            text = { Text(stringResource(Res.string.settings_forget_text)) },
            confirmButton = {
                TextButton(onClick = {
                    repo.setPairedServer(null)
                    paired = null
                    forgetting = false
                    message = null
                    onChanged()
                }) { Text(stringResource(Res.string.settings_forget)) }
            },
            dismissButton = { CancelButton(onClick = { forgetting = false }) },
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showTitle) Text(stringResource(Res.string.settings_pair_title), style = MaterialTheme.typography.titleSmall)
        val current = paired
        if (current != null) {
            Text(stringResource(Res.string.settings_paired_as, current.serverName, deviceName), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(
                    Res.string.settings_reached_at,
                    current.addresses.joinToString(", "),
                    current.port,
                    current.fingerprint.chunked(4).take(4).joinToString(" "),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        } else if (showHowTo) {
            Text(
                stringResource(Res.string.settings_pair_how_to),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (scan != null) {
                Button(onClick = scan, enabled = !busy && pairing != null) {
                    Icon(Icons.Filled.QrCodeScanner, contentDescription = null)
                    Text("  " + stringResource(if (current == null) Res.string.settings_scan_code else Res.string.settings_scan_again))
                }
            }
            if (current != null) {
                OutlinedButton(onClick = { forgetting = true }, enabled = !busy) { Text(stringResource(Res.string.settings_forget)) }
            }
        }
        OutlinedTextField(
            value = pasted,
            onValueChange = { pasted = it },
            label = { Text(stringResource(Res.string.settings_paste_link)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
            trailingIcon = {
                TextButton(onClick = { pairWith(pasted) }, enabled = pasted.isNotBlank() && !busy && pairing != null) { Text(stringResource(Res.string.settings_pair)) }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * The camera's QR code scanner: a function that opens it, after which [onScanned] receives what the
 * code says. Null where there is none here, and the link is pasted instead.
 */
@Composable
expect fun rememberPairingScanner(onScanned: (String) -> Unit): (() -> Unit)?

/** What the Devices page lists this device as: its own name, as its owner set it, where there is one. */
@Composable
expect fun rememberDeviceName(): String
