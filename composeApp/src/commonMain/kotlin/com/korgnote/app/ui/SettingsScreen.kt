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
import com.korgnote.app.data.buildBaseUrl
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(repo: SettingsRepository) {
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf("") }
    var portText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        host = repo.getServerHost()
        portText = repo.getServerPort().toString()
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
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(error!!)
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = {
            val port = portText.toIntOrNull()
            if (host.isBlank() || port == null || port !in 1..65535) {
                error = "Please enter a valid host and port (1-65535)."
                return@Button
            }
            error = null
            scope.launch {
                repo.setServerHost(host.trim())
                repo.setServerPort(port)
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
    }
}
