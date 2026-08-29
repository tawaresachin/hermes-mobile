package com.hermes.mobile.social

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Prompt marketplace for sharing community prompts.
 * 
 * Features:
 * - Browse popular prompts
 * - Share your own prompts
 * - Rate and save favorites
 */
@Composable
fun PromptMarketplace(
    prompts: List<Prompt>,
    onShare: (Prompt) -> Unit,
    onUse: (String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var showShareDialog by remember { mutableStateOf(false) }
    
    Column {
        // Search bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text("Search prompts...") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            singleLine = true
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Action bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${prompts.count { it.matchesSearch(searchQuery) }} prompts",
                style = MaterialTheme.typography.bodySmall
            )
            Button(
                onClick = { showShareDialog = true },
                enabled = prompts.isNotEmpty()
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Share")
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Prompt list
        LazyColumn(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(prompts.filter { it.matchesSearch(searchQuery) }) { prompt ->
                PromptCard(
                    prompt = prompt,
                    onShare = { onShare(prompt) },
                    onUse = { onUse(prompt.content) }
                )
            }
        }
    }
    
    // Share dialog
    if (showShareDialog) {
        SharePromptDialog(
            onDismiss = { showShareDialog = false },
            onShare = { _, _, _ ->
                // Share to marketplace (server-side store)
                showShareDialog = false
            }
        )
    }
}

@Composable
private fun PromptCard(
    prompt: Prompt,
    onShare: () -> Unit,
    onUse: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = prompt.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = prompt.content,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onShare) {
                    Icon(Icons.Default.Share, contentDescription = "Share")
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Tags
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                prompt.tags.take(3).forEach { tag ->
                    AssistChip(
                        onClick = {},
                        label = { Text(tag) }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Stats and actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$prompt.likes likes · ${prompt.formattedDate}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = onUse,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Use")
                }
            }
        }
    }
}

@Composable
private fun SharePromptDialog(
    onDismiss: () -> Unit,
    onShare: (String, String, List<String>) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf("") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share Prompt") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("Prompt content") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
                OutlinedTextField(
                    value = tags,
                    onValueChange = { tags = it },
                    label = { Text("Tags (comma separated)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val tagList = tags.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    onShare(title, content, tagList)
                },
                enabled = title.isNotBlank() && content.isNotBlank()
            ) {
                Text("Share")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

data class Prompt(
    val id: String,
    val title: String,
    val content: String,
    val tags: List<String>,
    val likes: Int = 0,
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val author: String = "Anonymous"
) {
    val formattedDate get() = createdAt.format(DateTimeFormatter.ofPattern("MMM d"))
    
    fun matchesSearch(query: String): Boolean {
        if (query.isBlank()) return true
        val q = query.lowercase()
        return title.lowercase().contains(q) || 
               content.lowercase().contains(q) ||
               tags.any { it.lowercase().contains(q) }
    }
}
