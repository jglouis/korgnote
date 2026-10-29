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
import kotlinx.coroutines.launch

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
) {
    val scope = rememberCoroutineScope()
    var paired by remember { mutableStateOf(repo.getPairedServer()) }
    var pasted by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var forgetting by remember { mutableStateOf(false) }
    val deviceName = rememberDeviceName()

    fun pairWith(text: String) {
        val link = PairingLink.parse(text)
        if (link == null) {
            message = "That is not a KorgNote pairing link. It starts with korgnote://pair."
            return
        }
        if (pairing == null) return
        scope.launch {
            busy = true
            message = "Pairing with ${link.serverName.ifBlank { "the computer" }}…"
            message = when (val result = pairing.pair(link, deviceName)) {
                is PairingResult.Paired -> {
                    paired = result.server
                    pasted = ""
                    onChanged()
                    "Paired with ${result.server.serverName}."
                }
                is PairingResult.Failed -> result.reason
            }
            busy = false
        }
    }

    LaunchedEffect(incomingLink) {
        incomingLink?.let {
            onIncomingLinkUsed()
            pairWith(it)
        }
    }
    val scan = rememberPairingScanner { pairWith(it) }

    if (forgetting) {
        AlertDialog(
            onDismissRequest = { forgetting = false },
            title = { Text("Forget ${paired?.serverName ?: "the computer"}?") },
            text = { Text("This device stops syncing until it is paired again. To make its access stop on the computer too, revoke it on the Devices page there.") },
            confirmButton = {
                TextButton(onClick = {
                    repo.setPairedServer(null)
                    paired = null
                    forgetting = false
                    message = null
                    onChanged()
                }) { Text("Forget") }
            },
            dismissButton = { TextButton(onClick = { forgetting = false }) { Text("Cancel") } },
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Pair with a computer")
        val current = paired
        if (current != null) {
            Text("Paired with ${current.serverName} as $deviceName.", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Reached at ${current.addresses.joinToString(" or ")}, port ${current.port}. " +
                    "Certificate ${current.fingerprint.chunked(4).take(4).joinToString(" ")}…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        } else if (showHowTo) {
            Text(
                "On the computer the server runs on, open http://localhost:8080/devices, " +
                    "press Show a pairing code, and scan it here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (scan != null) {
                Button(onClick = scan, enabled = !busy && pairing != null) {
                    Icon(Icons.Filled.QrCodeScanner, contentDescription = null)
                    Text(if (current == null) "  Scan the code" else "  Scan again")
                }
            }
            if (current != null) {
                OutlinedButton(onClick = { forgetting = true }, enabled = !busy) { Text("Forget") }
            }
        }
        OutlinedTextField(
            value = pasted,
            onValueChange = { pasted = it },
            label = { Text("Or paste the pairing link") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
            trailingIcon = {
                TextButton(onClick = { pairWith(pasted) }, enabled = pasted.isNotBlank() && !busy && pairing != null) { Text("Pair") }
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
