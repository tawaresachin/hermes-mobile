package com.hermes.mobile.ui.screens.settings

import android.net.Uri
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.hermes.mobile.ui.theme.*

/* ── Providers card: server-side model-provider CRUD ──
 * Rows come straight from the gateway (custom endpoints in the desktop's
 * config). Add/edit opens a modal sheet; keys are write-only (the server
 * returns a redacted preview). Validation probes the endpoint live before
 * anything is saved. */
@Composable
fun ProvidersCard(viewModel: SettingsViewModel, uiState: SettingsUiState) {
    var expanded by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var confirmDefault by remember { mutableStateOf<org.json.JSONObject?>(null) }
    fun sheetError(msg: String?) { msg ?: return
        viewModel.providersErrorNow(msg)
    }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth()
                .clickable {
                    expanded = !expanded
                    if (expanded && !uiState.providersLoaded) viewModel.loadProviders()
                }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Tune, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Model providers",
                    style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface)
                Text(if (expanded) "On the server — shared with desktop"
                     else "Tap to load from the server",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
            if (uiState.providersLoading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else if (expanded) {
                IconButton(onClick = { viewModel.loadProviders() },
                    modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Reload",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null)
            }
        }
        if (expanded) {
            uiState.providersError?.let { err ->
                Text(err, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 6.dp))
            }
            uiState.providers.forEach { ep ->
                val id = ep.optString("id")
                val isDefault = ep.optBoolean("is_current", false)
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(ep.optString("name").ifBlank { id },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface)
                            Text(ep.optString("base_url").removePrefix("https://")
                                    .removePrefix("http://")
                                    .let { if (it.length > 34) it.take(34) + "…" else it } +
                                    " · " + modelCountOf(ep.optJSONArray("models")) +
                                    " models" +
                                    (if (ep.optBoolean("has_api_key", false)) " · key set" else ""),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (isDefault) {
                            Surface(color = HermesPrimary.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(8.dp)) {
                                Text("DEFAULT", modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = HermesPrimary)
                            }
                        } else {
                            IconButton(onClick = { confirmDefault = ep },
                                modifier = Modifier.size(36.dp).minimumInteractiveComponentSize()) {
                                Icon(Icons.Filled.StarOutline, "Set default",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        IconButton(onClick = { editing = ep; showEditor = true },
                            modifier = Modifier.size(36.dp).minimumInteractiveComponentSize()) {
                            Icon(Icons.Filled.Edit, "Edit", modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { confirmDelete = id },
                            modifier = Modifier.size(36.dp).minimumInteractiveComponentSize()) {
                            Icon(Icons.Filled.DeleteOutline, "Delete", modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                HorizontalDivider(thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
            OutlinedButton(
                onClick = { editing = null; showEditor = true },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                shape = RoundedCornerShape(10.dp)) {
                Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Add provider")
            }
            Text("Any OpenAI-compatible endpoint (ollama, LM Studio, groq…). Saved on the server.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 6.dp))
        }
    }

    if (showEditor) {
        ProviderEditorSheet(
            existing = editing,
            onDismiss = { showEditor = false },
            onValidate = { url, key, cb ->
                viewModel.validateProvider(url, key) { ok, reachable, msg, models ->
                    cb(ok, msg.ifBlank { if (reachable) "" else "Could not reach $url" }, models)
                }
            },
            onSave = { id, name, url, key, models, asDef, done ->
                viewModel.saveProvider(id, name, url, key, models, asDef) { ok, err ->
                    if (ok) showEditor = false
                    done(ok, err)
                }
            },
        )
    }

    confirmDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete provider?") },
            text = { Text("The endpoint and its stored key are removed from the server. It will drop out of the model picker.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    viewModel.deleteProvider(id) { ok, err -> if (!ok) sheetError(err) }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }

    confirmDefault?.let { ep ->
        AlertDialog(
            onDismissRequest = { confirmDefault = null },
            title = { Text("Set ${ep.optString("name")} as default?") },
            text = { Text("New chats will use this provider. Existing sessions keep their own model.") },
            confirmButton = {
                TextButton(onClick = {
                    val eid = ep.optString("id")
                    confirmDefault = null
                    viewModel.activateProvider(eid) { ok, err -> if (!ok) sheetError(err) }
                }) { Text("Set default") }
            },
            dismissButton = { TextButton(onClick = { confirmDefault = null }) { Text("Cancel") } },
        )
    }
}


@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ProviderEditorSheet(
    existing: org.json.JSONObject?,
    onDismiss: () -> Unit,
    onValidate: (String, String?, (ok: Boolean, message: String, models: List<String>) -> Unit) -> Unit,
    onSave: (id: String, name: String, url: String,
             key: String?, models: List<String>, asDefault: Boolean,
             done: (Boolean, String?) -> Unit) -> Unit,
) {
    var name by remember { mutableStateOf(existing?.optString("name").orEmpty()) }
    var url by remember { mutableStateOf(existing?.optString("base_url").orEmpty()) }
    var key by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var models: List<String> by remember {
        mutableStateOf(existing?.optJSONArray("models")?.let { a ->
            (0 until a.length()).map { a.getString(it) } ?: emptyList<String>()
        } ?: emptyList<String>())
    }
    var selected by remember {
        mutableStateOf(existing?.optJSONArray("models")?.let { a ->
            (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet())
    }
    var validating by remember { mutableStateOf(false) }
    var valMsg by remember { mutableStateOf<String?>(null) }
    var valOk by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveErr by remember { mutableStateOf<String?>(null) }
    val hasKey = existing?.optBoolean("has_api_key", false) ?: false

    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text(if (existing == null) "Add provider" else "Edit provider",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(value = name, onValueChange = { name = it; valMsg = null },
                label = { Text("Name") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp))
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = url, onValueChange = { url = it; valMsg = null },
                label = { Text("Base URL") },
                singleLine = true, keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp))
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = key,
                onValueChange = { key = it; valMsg = null },
                label = { Text(if (hasKey && key.isEmpty()) "API key (leave empty to keep)" else "API key") },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showKey = !showKey }) {
                        Icon(if (showKey) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                            null, modifier = Modifier.size(18.dp))
                    }
                },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp))
            Spacer(Modifier.height(10.dp))
            if (valMsg != null) {
                Surface(color = if (valOk) androidx.compose.material3.MaterialTheme.colorScheme
                        .secondaryContainer.copy(alpha = 0.5f)
                    else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(10.dp)) {
                    Text(valMsg!!, Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (valOk) MaterialTheme.colorScheme.onSecondaryContainer
                                else MaterialTheme.colorScheme.onErrorContainer)
                }
                Spacer(Modifier.height(10.dp))
            }
            if (models.isNotEmpty()) {
                Text("Models${if (selected.isEmpty()) " (none selected: all)" else " (${selected.size} selected)"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                // Bounded, scrollable region: the list can grow long, but the
                // Save/Check row below it stays visible. One model per row with
                // its FULL name — the old 6-chips-per-row layout cut long names.
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    models.forEach { m ->
                        val on = selected.isEmpty() || selected.contains(m)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = on,
                                onCheckedChange = {
                                    val cur = if (selected.isEmpty()) models.toSet() else selected
                                    val next = cur.toMutableSet()
                                    if (!next.add(m)) next.remove(m)
                                    selected = next
                                }
                            )
                            Text(m,
                                Modifier.weight(1f).padding(start = 4.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (on) MaterialTheme.colorScheme.onSurface
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            saveErr?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    enabled = !saving && !validating &&
                        name.isNotBlank() && url.isNotBlank(),
                    onClick = {
                        saving = true; saveErr = null
                        onSave(existing?.optString("id").orEmpty(), name.trim(), url.trim(),
                            key.takeIf { it.isNotBlank() },
                            models.filter { selected.isEmpty() || selected.contains(it) },
                            false) { ok, err ->
                            saving = false
                            if (!ok) saveErr = err
                        }
                    },
                    modifier = Modifier.weight(2f), shape = RoundedCornerShape(22.dp)) {
                    if (saving) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text(if (existing == null) "Save" else "Update")
                }
                OutlinedButton(
                    enabled = !validating && url.isNotBlank(),
                    onClick = {
                        validating = true; valMsg = null
                        onValidate(url.trim(), key.takeIf { it.isNotBlank() }) { ok, msg, found ->
                            validating = false; valOk = ok
                            if (ok && found.isNotEmpty()) {
                                models = found
                                if (selected.isEmpty()) selected = found.toSet()
                                valMsg = "Endpoint OK — ${found.size} models"
                            } else {
                                valMsg = msg.ifBlank { "Validation failed" }
                            }
                        }
                    },
                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(22.dp)) {
                    if (validating) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text("Check")
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}


private fun modelCountOf(a: org.json.JSONArray?): Int = a?.length() ?: 0
