package com.hermes.mobile.ui.screens.terminal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.verification.PromiscuousVerifier

@Composable
fun TerminalScreen(paddingValues: PaddingValues = PaddingValues(0.dp)) {
    var host by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var connected by remember { mutableStateOf(false) }
    var output by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val sessionHolder = remember { mutableStateOf<Session?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Terminal, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text("Terminal (SSH/PTY)", style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        }
        Spacer(Modifier.height(12.dp))
        if (!connected) {
            OutlinedTextField(host, { host = it }, label = { Text("Host") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(user, { user = it }, label = { Text("Username") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(pass, { pass = it }, label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    running = true
                    output = ""
                    scope.launch {
                        val res = withContext(Dispatchers.IO) { connectPty(host, user, pass) { chunk -> output += chunk } }
                        sessionHolder.value = res.first
                        if (res.second.isNotBlank()) output += res.second
                        connected = res.first != null
                        running = false
                    }
                },
                enabled = !running && host.isNotBlank() && user.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (running) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("Connect")
            }
        } else {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp)
            ) {
                Text(
                    text = output.ifBlank { "(connected)" },
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(8.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(input, { input = it }, label = { Text("Input") }, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    val s = sessionHolder.value
                    try { s?.outputStream?.write((input + "\n").toByteArray()); s?.outputStream?.flush() } catch (_: Exception) {}
                    input = ""
                }) { Text("Send") }
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                try { sessionHolder.value?.close() } catch (_: Exception) {}
                sessionHolder.value = null
                connected = false
                output = ""
            }, modifier = Modifier.fillMaxWidth()) { Text("Disconnect") }
        }
    }
}

private fun connectPty(host: String, user: String, pass: String, onChunk: (String) -> Unit): Pair<Session?, String> {
    val ssh = SSHClient()
    return try {
        ssh.addHostKeyVerifier(PromiscuousVerifier())
        ssh.connect(host, 22)
        ssh.authPassword(user, pass)
        val session = ssh.startSession()
        session.allocateDefaultPTY()
        val shell = session.startShell()
        val reader = Thread {
            try {
                val buf = ByteArray(4096)
                while (true) {
                    val n = shell.inputStream.read(buf)
                    if (n < 0) break
                    onChunk(String(buf, 0, n))
                }
            } catch (_: Exception) {}
        }
        reader.isDaemon = true
        reader.start()
        Pair(session, "")
    } catch (e: Exception) {
        Pair(null, "ERROR: ${e.message}")
    }
}