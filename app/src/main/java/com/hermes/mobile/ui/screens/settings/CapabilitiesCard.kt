package com.hermes.mobile.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermes.mobile.ui.theme.*

/* ── Capabilities card: server-side capability inspection ──
 * Plugins, MCP servers, projects and skills. Row counts are small, so the
 * lists use Column + forEach — never a nested LazyColumn (CR-1 crash).
 * Every section has its own LOADED(empty) / FAILED state so "no rows" and
 * "the route failed" are never the same picture (ME-6).
 */
@Composable
fun CapabilitiesCard(viewModel: SettingsViewModel, uiState: SettingsUiState) {
    CapabilitiesCardContent(
        capabilities = uiState.capabilities,
        onLoad = { viewModel.loadCapabilities() },
        onCancel = { viewModel.cancelCapabilitiesLoad() },
        onToggleSkill = { name, enabled -> viewModel.toggleCapabilitySkill(name, enabled) },
    )
}

/** Stateless overload: usable from a Compose test without a Hilt ViewModel. */
@Composable
fun CapabilitiesCardContent(
    capabilities: CapabilitiesState,
    onLoad: () -> Unit,
    onCancel: () -> Unit,
    onToggleSkill: (String, Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { onCancel() } }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth()
                .testTag("capabilities_header")
                .clickable {
                    expanded = !expanded
                    if (expanded) {
                        if (!capabilities.loaded) onLoad()
                    } else {
                        onCancel()
                    }
                }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Extension, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Capabilities",
                    style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface)
                Text(if (expanded) "Plugins, MCP servers, projects, and skills on the server"
                     else "Tap to load from the server",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
            if (capabilities.loading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else if (expanded) {
                IconButton(onClick = onLoad, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Reload",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Filled.ExpandLess, contentDescription = null)
            }
        }
        if (expanded) {
            // ── Plugins ──
            CapabilitySection(
                title = "Plugins", icon = Icons.Filled.Extension, iconColor = HermesPrimary,
                state = capabilities.plugins, emptyMessage = "No plugins installed",
            )
            SectionDivider()

            // ── MCP Servers ──
            CapabilitySection(
                title = "MCP Servers", icon = Icons.Filled.Dns, iconColor = SuccessGreen,
                state = capabilities.mcpServers, emptyMessage = "No MCP servers configured",
            )
            SectionDivider()

            // ── Projects ──
            CapabilitySection(
                title = "Projects", icon = Icons.Filled.Folder, iconColor = WarningAmber,
                state = capabilities.projects, emptyMessage = "No projects configured",
            )
            SectionDivider()

            // ── Skills ──
            CapabilitySkillsSection(
                state = capabilities.skills,
                onToggle = onToggleSkill,
                loading = capabilities.loading,
            )

            Text("Capabilities are read from the server's configuration. Skills can be enabled/disabled here.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
private fun SectionHeading(title: String, icon: ImageVector, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = color)
    }
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
private fun SectionNote(text: String, isError: Boolean = false) {
    Text(text, style = MaterialTheme.typography.bodySmall,
        color = if (isError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
}

@Composable
private fun CapabilitySection(
    title: String,
    icon: ImageVector,
    iconColor: Color,
    state: CapabilitySectionState,
    emptyMessage: String,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        SectionHeading(title, icon, iconColor)
        when (state.status) {
            CapabilitySectionStatus.IDLE -> SectionNote("Not loaded yet")
            CapabilitySectionStatus.FAILED -> SectionNote(
                state.error ?: "Could not load $title", isError = true)
            CapabilitySectionStatus.LOADED -> {
                if (state.items.isEmpty()) SectionNote(emptyMessage)
                else state.items.forEachIndexed { i, item ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(item.name, style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium)
                                Text(item.description.ifBlank { "—" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(item.detail.ifBlank { "—" },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            item.enabled?.let { enabled ->
                                Surface(
                                    color = if (enabled) SuccessGreen.copy(alpha = 0.15f)
                                            else MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(if (enabled) "ENABLED" else "DISABLED",
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (enabled) SuccessGreen
                                                else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    if (i < state.items.lastIndex) {
                        HorizontalDivider(thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                            modifier = Modifier.padding(vertical = 6.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun CapabilitySkillsSection(
    state: CapabilitySectionState,
    onToggle: (String, Boolean) -> Unit,
    loading: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        SectionHeading("Skills", Icons.Filled.Psychology, MaterialTheme.colorScheme.primary)
        state.toggleError?.let { SectionNote(it, isError = true) }
        when (state.status) {
            CapabilitySectionStatus.IDLE -> SectionNote("Not loaded yet")
            CapabilitySectionStatus.FAILED -> SectionNote(
                state.error ?: "Could not load skills", isError = true)
            CapabilitySectionStatus.LOADED -> {
                if (state.items.isEmpty()) SectionNote("No skills found")
                else state.items.forEachIndexed { i, skill ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Row(Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(skill.name, style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium)
                                    if (skill.essential) {
                                        Spacer(Modifier.width(6.dp))
                                        Surface(color = WarningAmber.copy(alpha = 0.15f),
                                            shape = RoundedCornerShape(6.dp)) {
                                            Text("ESSENTIAL", modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                                style = MaterialTheme.typography.labelSmall, color = WarningAmber)
                                        }
                                    }
                                }
                                Text(skill.description.ifBlank { "—" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (skill.category.isBlank()) "Category: —" else "Category: ${skill.category}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                            }
                            Switch(
                                checked = skill.enabled ?: true,
                                onCheckedChange = { newValue ->
                                    if (!skill.essential || newValue) onToggle(skill.name, newValue)
                                },
                                enabled = !loading && (!skill.essential || skill.enabled != false),
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = HermesPrimary,
                                    checkedTrackColor = HermesPrimary.copy(alpha = 0.5f),
                                    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    uncheckedTrackColor = MaterialTheme.colorScheme.outlineVariant
                                )
                            )
                        }
                    }
                    if (i < state.items.lastIndex) {
                        HorizontalDivider(thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                            modifier = Modifier.padding(vertical = 6.dp))
                    }
                }
            }
        }
    }
}
