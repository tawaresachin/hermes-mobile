package com.hermes.mobile.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * Telegram-style rename dialog for a session.
 * Long-press a session row anywhere to open it.
 */
@Composable
fun RenameSessionDialog(
    currentTitle: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit
) {
    var name by remember(currentTitle) { mutableStateOf(currentTitle) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename session") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Name") },
                placeholder = { Text("Untitled Session") }
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val trimmed = name.trim()
                    onRename(if (trimmed.isEmpty()) "Untitled Session" else trimmed)
                }
            ) { Text("Save", color = MaterialTheme.colorScheme.primary) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
