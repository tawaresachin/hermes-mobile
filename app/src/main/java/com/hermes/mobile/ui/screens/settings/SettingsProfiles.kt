package com.hermes.mobile.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.hermes.mobile.ui.theme.*

/* ── Profiles card: server-side agent profile CRUD with SOUL.md ──
 * Profiles are like "bots" with their own model, provider, description, and SOUL.md.
 * Each profile can be set as active (the gateway will use it for new sessions).
 * Rows are small and typed (AgentProfile): no JSONObject in UiState (ME-9).
 */

@Composable
fun ProfilesCard(viewModel: SettingsViewModel, uiState: SettingsUiState) {
    var expanded by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AgentProfile?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var showSoulEditor by remember { mutableStateOf<AgentProfile?>(null) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var confirmSetActive by remember { mutableStateOf<AgentProfile?>(null) }
    var soulContent by remember { mutableStateOf("") }
    var soulLoading by remember { mutableStateOf(false) }
    var soulError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) { onDispose { viewModel.cancelProfilesLoad() } }

    fun sheetError(msg: String?) { msg ?: return
        viewModel.profilesErrorNow(msg)
    }

    LaunchedEffect(showSoulEditor) {
        val ep = showSoulEditor ?: return@LaunchedEffect
        soulLoading = true
        soulError = null
        soulContent = ""
        viewModel.loadAgentProfileSoul(ep.name) { loaded, err ->
            // A read failure is never presented as an empty document (ME-13).
            soulContent = loaded.orEmpty()
            soulError = err
            soulLoading = false
        }
    }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth()
                .clickable {
                    expanded = !expanded
                    if (expanded && !uiState.profilesLoaded) {
                        viewModel.loadAgentProfiles()
                    } else if (!expanded) {
                        viewModel.cancelProfilesLoad()
                    }
                }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Person, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Agent Profiles",
                    style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface)
                Text(if (expanded) "Bot profiles with SOUL.md on the server"
                     else "Tap to load from the server",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
            if (uiState.profilesLoading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else if (expanded) {
                IconButton(onClick = { viewModel.loadAgentProfiles() },
                    modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Reload",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Filled.ExpandLess, contentDescription = null)
            }
        }
        if (expanded) {
            uiState.profilesError?.let { err ->
                Text(err, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 6.dp))
            }
            uiState.activeProfile?.let { active ->
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    color = HermesPrimary.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Star, contentDescription = null,
                                tint = HermesPrimary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Active Profile",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = HermesPrimary)
                            Spacer(Modifier.width(8.dp))
                            Text(active.displayName.ifBlank { active.name },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth()) {
                            Text(active.description.ifBlank { "No description" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f))
                            IconButton(onClick = { showSoulEditor = active },
                                modifier = Modifier.size(36.dp).minimumInteractiveComponentSize()) {
                                Icon(Icons.Filled.Edit, "Edit SOUL.md", modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            uiState.profiles.forEach { profile ->
                val name = profile.name
                val isActive = uiState.activeProfile?.name == name
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(profile.displayName.ifBlank { name },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                "Model: ${profile.model.ifBlank { "default" }} · " +
                                    "Provider: ${profile.provider.ifBlank { "default" }} · " +
                                    "Skills: ${profile.skillCount} · " +
                                    (if (profile.hasEnv) "· .env set" else ""),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (profile.isDefault) {
                            Surface(color = HermesPrimary.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(8.dp)) {
                                Text("DEFAULT", modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = HermesPrimary)
                            }
                        } else if (isActive) {
                            Surface(color = SuccessGreen.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(8.dp)) {
                                Text("ACTIVE", modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = SuccessGreen)
                            }
                        } else {
                            IconButton(onClick = { confirmSetActive = profile },
                                modifier = Modifier.size(36.dp).minimumInteractiveComponentSize()) {
                                Icon(Icons.Filled.StarOutline, "Set active",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        IconButton(onClick = { showSoulEditor = profile },
                            modifier = Modifier.size(36.dp).minimumInteractiveComponentSize()) {
                            Icon(Icons.Filled.Edit, "Edit SOUL.md", modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { editing = profile; showEditor = true },
                            modifier = Modifier.size(36.dp).minimumInteractiveComponentSize()) {
                            Icon(Icons.Filled.Edit, "Edit", modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        // Never delete the ACTIVE profile from the phone: the
                        // gateway would be left without the bot it is told to
                        // use. Switch to another profile first (star icon).
                        if (!profile.isDefault && !isActive) {
                            IconButton(onClick = { confirmDelete = name },
                                modifier = Modifier.size(36.dp).minimumInteractiveComponentSize()) {
                                Icon(Icons.Filled.DeleteOutline, "Delete", modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    HorizontalDivider(thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
            OutlinedButton(
                onClick = { editing = null; showEditor = true },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                shape = RoundedCornerShape(10.dp)) {
                Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Create Profile")
            }
            Text("Profiles are bot configurations with model, provider, and SOUL.md. Saved on the server.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 6.dp))
        }
    }

    if (showEditor) {
        ProfileEditorSheet(
            existing = editing,
            onDismiss = { showEditor = false; editing = null },
            onSave = { name, displayName, description, model, provider, done ->
                if (editing != null) {
                    viewModel.updateAgentProfile(
                        // The PATCH route keys on the path slug; a body "name"
                        // is ignored, so never pretend the slug can be renamed.
                        name = editing!!.name,
                        displayName = displayName,
                        description = description,
                        model = model?.takeIf { it.isNotBlank() },
                        provider = provider?.takeIf { it.isNotBlank() }
                    ) { ok, err ->
                        if (ok) showEditor = false
                        done(ok, err)
                    }
                } else {
                    viewModel.createAgentProfile(
                        name = name,
                        displayName = displayName,
                        description = description,
                        model = model?.takeIf { it.isNotBlank() },
                        provider = provider?.takeIf { it.isNotBlank() },
                        soul = null
                    ) { ok, err ->
                        if (ok) showEditor = false
                        done(ok, err)
                    }
                }
            },
        )
    }

    showSoulEditor?.let { profile ->
        SoulEditorSheet(
            profile = profile,
            content = soulContent,
            loading = soulLoading,
            error = soulError,
            onDismiss = { showSoulEditor = null },
            onSave = { content, done ->
                viewModel.saveAgentProfileSoul(profile.name, content) { ok, err ->
                    if (ok) showSoulEditor = null
                    done(ok, err)
                }
            },
        )
    }

    confirmDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete profile?") },
            text = { Text("The profile and its SOUL.md will be removed from the server.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    viewModel.deleteAgentProfile(name) { ok, err -> if (!ok) sheetError(err) }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }

    confirmSetActive?.let { profile ->
        AlertDialog(
            onDismissRequest = { confirmSetActive = null },
            title = { Text("Set ${profile.displayName.ifBlank { profile.name }} as active?") },
            text = { Text("New sessions will use this profile's model and provider.") },
            confirmButton = {
                TextButton(onClick = {
                    val name = profile.name
                    confirmSetActive = null
                    viewModel.setActiveAgentProfile(name) { ok, err -> if (!ok) sheetError(err) }
                }) { Text("Set Active") }
            },
            dismissButton = { TextButton(onClick = { confirmSetActive = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ProfileEditorSheet(
    existing: AgentProfile?,
    onDismiss: () -> Unit,
    onSave: (name: String, displayName: String, description: String, model: String?, provider: String?, done: (Boolean, String?) -> Unit) -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var displayName by remember { mutableStateOf(existing?.displayName.orEmpty()) }
    var description by remember { mutableStateOf(existing?.description.orEmpty()) }
    var model by remember { mutableStateOf(existing?.model.orEmpty()) }
    var provider by remember { mutableStateOf(existing?.provider.orEmpty()) }
    var saving by remember { mutableStateOf(false) }
    var saveErr by remember { mutableStateOf<String?>(null) }
    val isEditing = existing != null

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text(if (isEditing) "Edit Profile" else "Create Profile",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(value = name, onValueChange = { name = it; saveErr = null },
                label = { Text("Profile Name (slug)") },
                enabled = !isEditing,
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp))
            if (isEditing) Text("The slug is fixed; rename via Display Name.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = displayName, onValueChange = { displayName = it; saveErr = null },
                label = { Text("Display Name") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp))
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = description, onValueChange = { description = it; saveErr = null },
                label = { Text("Description") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp))
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = model, onValueChange = { model = it; saveErr = null },
                label = { Text("Model ID (optional, leave empty for server default)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp))
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = provider, onValueChange = { provider = it; saveErr = null },
                label = { Text("Provider Slug (optional, e.g. custom:freellm)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp))
            saveErr?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    enabled = !saving &&
                        name.isNotBlank() && displayName.isNotBlank(),
                    onClick = {
                        saving = true; saveErr = null
                        onSave(name.trim(), displayName.trim(), description.trim(),
                            model.takeIf { it.isNotBlank() },
                            provider.takeIf { it.isNotBlank() }
                        ) { ok, err ->
                            saving = false
                            if (!ok) saveErr = err
                        }
                    },
                    modifier = Modifier.weight(2f), shape = RoundedCornerShape(22.dp)) {
                    if (saving) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text(if (isEditing) "Update" else "Create")
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SoulEditorSheet(
    profile: AgentProfile,
    content: String,
    loading: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (content: String, done: (Boolean, String?) -> Unit) -> Unit,
) {
    var draft by remember(content) { mutableStateOf(content) }
    var saving by remember { mutableStateOf(false) }
    var saveErr by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text("Edit SOUL.md — ${profile.displayName.ifBlank { profile.name }}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(14.dp))

            when {
                error != null -> Text(error, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
                loading -> Box(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                }
                else -> {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it; saveErr = null },
                        label = { Text("SOUL.md content (Markdown)") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp),
                        shape = RoundedCornerShape(10.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        visualTransformation = VisualTransformation.None,
                    )
                    saveErr?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    enabled = !saving && !loading && error == null && draft.isNotBlank(),
                    onClick = {
                        saving = true; saveErr = null
                        onSave(draft) { ok, err ->
                            saving = false
                            if (!ok) saveErr = err
                        }
                    },
                    modifier = Modifier.weight(2f), shape = RoundedCornerShape(22.dp)) {
                    if (saving) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text("Save")
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
