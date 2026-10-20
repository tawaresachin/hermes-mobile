package com.hermes.mobile.ui.screens.settings

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermes.mobile.ui.theme.*
import kotlinx.coroutines.flow.update

// ── Server panel: cron jobs + skills, live from the gateway ──
// Collapsed by default (one tap to load — keeps Settings instant). Jobs get
// Run-now + Pause/Resume; skills are a read-only list (the /skills slash
// text dump, upgraded to a browsable screen).
/** About-section row for the server itself (replaces the old Server card):
 * "Hermes Agent · v0.21.1 · b2aa855" + a round check arrow. The arrow runs
 * `hermes update --check` server-side; when an update is found it toggles
 * into an Update button that triggers the OFFICIAL pipeline (`hermes update`
 * + gateway restart, detached on the host) and re-checks itself when the
 * server is back. Tap the row at any time to re-check. */
@Composable
fun HermesAgentAboutRow(viewModel: SettingsViewModel) {
    val state by viewModel.updateState.collectAsState()
    LaunchedEffect(Unit) {
        if (state.installedVersion.isEmpty() && state.currentSha.isEmpty())
            viewModel.loadInstalledVersion()
    }
    val versionLabel = listOfNotNull(
        state.installedVersion.takeIf { it.isNotBlank() }?.let { "v$it" },
        state.currentSha.takeIf { it.isNotBlank() },
    ).joinToString(" · ")
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(enabled = !state.loading && !state.applying) {
                // Cached server-side (plugin TTL) — a fresh=true tap busted
                // the cache and re-ran the slow GitHub probe every tap, so
                // the row sat spinning ("looping"). Cold cache still does
                // one full check; repeats are instant.
                viewModel.loadUpdateInfo(fresh = false)
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Agent Version",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            Text(
                text = when {
                    state.loading -> "checking…"
                    state.applying -> "updating…"
                    versionLabel.isNotBlank() -> versionLabel
                    else -> "—"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            val sub = when {
                state.error != null && !state.loading -> state.error
                else -> null
            }
            if (sub != null) Text(
                sub, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        when {
            state.loading || state.applying -> CircularProgressIndicator(
                modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            state.supported && !state.upToDate -> FilledTonalButton(
                onClick = { viewModel.applyUpdate() },
                modifier = Modifier.height(26.dp).defaultMinSize(minWidth = 0.dp, minHeight = 0.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            ) { Text("Update", fontSize = 13.sp) }
            else -> Icon(Icons.Filled.Refresh, contentDescription = "Check for update",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp))
        }
    }
}

/** App self-update row (GitHub Releases, 12h throttle inside the
 * checker): "Version · vCurrent · ↻" — flips to an Update button when a
 * newer signed release exists; tap → downloads, verifies SHA256, opens
 * the system installer (updates in place, same key). Mirrors the
 * Agent Version row's design language. */

@Composable
fun HermesAppVersionRow(viewModel: SettingsViewModel) {
    val state by viewModel.appUpdateState.collectAsState()
    LaunchedEffect(Unit) { viewModel.checkAppUpdate() }
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(enabled = !state.checking && !state.downloading) {
                viewModel.checkAppUpdate()
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Version",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            Text(
                text = when {
                    state.checking -> "checking…"
                    state.downloading -> "downloading ${(state.progress * 100).toInt()}%"
                    else -> "v${state.current}"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            val sub = when {
                state.upToDate -> "Up to date"
                state.available -> "v${state.latest} available"
                state.error != null && !state.checking -> state.error
                else -> null
            }
            if (sub != null) Text(
                sub, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        when {
            state.checking || state.downloading -> CircularProgressIndicator(
                modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            state.available -> FilledTonalButton(
                onClick = { viewModel.downloadAppUpdate() },
                modifier = Modifier.height(26.dp).defaultMinSize(minWidth = 0.dp, minHeight = 0.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            ) { Text("Update", fontSize = 13.sp) }
            else -> Icon(Icons.Filled.Refresh, contentDescription = "Check for update",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp))
        }
    }
}
