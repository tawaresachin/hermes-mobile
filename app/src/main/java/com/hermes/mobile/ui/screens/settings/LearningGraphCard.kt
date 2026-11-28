package com.hermes.mobile.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Read-only view of the host's learning graph. No local graph is built here. */
@Composable
fun LearningGraphCard(viewModel: SettingsViewModel, uiState: SettingsUiState) {
    LearningGraphCardContent(
        state = uiState.learningGraph,
        activeProfile = uiState.activeProfile?.name,
        onLoad = { viewModel.loadLearningGraph() },
        onCancel = { viewModel.cancelLearningGraphLoad() },
    )
}

@Composable
fun LearningGraphCardContent(
    state: LearningGraphState,
    activeProfile: String? = null,
    onLoad: () -> Unit = {},
    onCancel: () -> Unit = {},
) {
    var expanded by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    DisposableEffect(Unit) { onDispose { onCancel() } }

    val scope = state.profile ?: activeProfile ?: "default"
    Column {
        Row(
            modifier = Modifier.fillMaxWidth()
                .clickable {
                    expanded = !expanded
                    if (expanded && state.status == LearningGraphStatus.IDLE) onLoad()
                    else if (!expanded) onCancel()
                }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Share, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Learning graph", style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                Text(if (expanded) "Skills and memory from the active agent profile"
                     else "Tap to load the host graph for $scope",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
            if (state.loading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else if (expanded) {
                IconButton(onClick = onLoad, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Reload")
                }
            }
        }

        if (!expanded) return@Column

        when {
            state.loading -> Text("Reading the host graph…", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp))
            state.status == LearningGraphStatus.FAILED -> Text(
                state.error ?: "Could not load the learning graph.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 8.dp))
            state.status == LearningGraphStatus.LOADED -> {
                val skills = state.stats["learned_skills"] ?: state.nodes.count { it.kind == "skill" }.toString()
                val memory = state.stats["memory_nodes"] ?: state.nodes.count { it.kind == "memory" }.toString()
                val edges = state.stats["related_edges"] ?: state.edges.size.toString()
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.padding(bottom = 8.dp)) {
                    GraphStat("Skills", skills)
                    GraphStat("Memory", memory)
                    GraphStat("Edges", edges)
                    GraphStat("Clusters", state.clusters.size.toString())
                }

                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    label = { Text("Filter nodes") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                )

                val labels = state.nodes.associateBy { it.id }
                val query = filter.trim()
                val nodes = if (query.isBlank()) state.nodes else state.nodes.filter {
                    it.label.contains(query, ignoreCase = true) || it.category.contains(query, ignoreCase = true)
                }
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                    nodes.forEach { node ->
                        Text(
                            text = listOfNotNull(
                                node.label, node.kind.takeIf { it.isNotBlank() },
                                node.category.takeIf { it.isNotBlank() },
                                node.detail.takeIf { it.isNotBlank() },
                                node.useCount.takeIf { it > 0 }?.let { "$it uses" },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                    if (state.edges.isNotEmpty()) {
                        Text("Links", style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                        state.edges.forEach { edge ->
                            val from = labels[edge.source]?.label ?: edge.source
                            val to = labels[edge.target]?.label ?: edge.target
                            Text("$from ↔ $to", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 2.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GraphStat(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
