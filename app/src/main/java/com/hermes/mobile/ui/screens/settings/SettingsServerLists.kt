package com.hermes.mobile.ui.screens.settings

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermes.mobile.ui.theme.*

@Composable
fun ServerListsPanel(viewModel: SettingsViewModel, uiState: SettingsUiState) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Row(
            modifier = Modifier.fillMaxWidth()
                .clickable {
                    expanded = !expanded
                    if (expanded && !uiState.serverListsLoaded) {
                        viewModel.loadServerLists()
                    }
                }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Schedule, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Scheduled jobs & skills",
                    style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface)
                Text(if (expanded) "From the gateway" else "Tap to load from the gateway",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
            if (uiState.serverListsLoading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                if (expanded) {
                    IconButton(onClick = { viewModel.loadServerLists() },
                        modifier = Modifier.size(32.dp).minimumInteractiveComponentSize()) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Reload",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null)
            }
        }
        if (expanded) {
            uiState.serverListsError?.let { msg ->
                Text(msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 6.dp))
            }
            // A partial success (jobs but no skills, or reverse) still shows
            // what the gateway did return, under the error line.
            if (uiState.serverListsError == null ||
                uiState.jobs.isNotEmpty() || uiState.skills.isNotEmpty()) {
                if (uiState.jobs.isEmpty()) {
                    Text("No jobs reported by the server.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 6.dp))
                }
                uiState.jobs.forEach { job ->
                    val id = job.optString("id")
                    val name = job.optString("name").ifBlank { id }
                    val enabled = job.optBoolean("enabled", true)
                    val state = job.optString("state")
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(name, style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface)
                            Text(job.optString("schedule_display") +
                                    (if (!enabled || state == "paused") " · paused" else "") +
                                    (job.optString("next_run_at").take(10).let {
                                        if (it.isNotBlank() && enabled) " · next $it" else "" }),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { viewModel.runJobNow(id) }) { Text("Run") }
                        TextButton(onClick = { viewModel.toggleJobPaused(id, !enabled) }) {
                            Text(if (enabled) "Pause" else "Resume")
                        }
                    }
                    HorizontalDivider(thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text("Skills (${uiState.skills.size})",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                uiState.skills.forEach { (n, d) ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = null,
                            tint = HermesPrimary.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(n, style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                            if (d.isNotBlank()) Text(d, style = MaterialTheme.typography.labelSmall,
                                maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
