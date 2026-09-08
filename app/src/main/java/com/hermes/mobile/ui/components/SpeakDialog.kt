package com.hermes.mobile.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.hermes.mobile.ui.screens.voice.VoiceViewModel
import com.hermes.mobile.ui.theme.HermesPrimary

@Composable
fun SpeakDialog(
    onDismiss: () -> Unit,
    onSpoken: (String) -> Unit
) {
    val vm: VoiceViewModel = hiltViewModel()
    var input by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Speak") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("Text to speak") },
                    placeholder = { Text("Enter text...") },
                    singleLine = false,
                    maxLines = 6,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    shape = RoundedCornerShape(16.dp)
                )
                if (loading) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(" Synthesizing...", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
                    }
                }
                error?.let { err ->
                    Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (input.isBlank()) return@Button
                    loading = true
                    error = null
                    vm.speak(input) { dataUrl ->
                        onSpoken(dataUrl)
                        onDismiss()
                    }.onFailure { e ->
                        error = e.message
                        loading = false
                    }
                },
                enabled = !loading && input.isNotBlank(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Speak")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}