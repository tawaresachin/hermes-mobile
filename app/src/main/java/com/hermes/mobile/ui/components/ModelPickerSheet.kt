package com.hermes.mobile.ui.components

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermes.mobile.data.local.FavoriteKeys
import com.hermes.mobile.data.local.ModelFavoritesStore
import com.hermes.mobile.data.model.ModelInfo
import com.hermes.mobile.ui.theme.HermesPrimary
import com.hermes.mobile.ui.theme.SuccessGreen

private const val PICKER_PREFS = "hermes_model_picker"
private const val KEY_ALL_MODELS = "all_models"

/**
 * Shared model picker bottom sheet — used by both Chat and Voice screens.
 * Live model list (never hardcoded), search, provider grouping, and a
 * "Global" toggle (switches the server default vs. session model).
 * Starred models stay in their provider group with a filled star; a Favorites
 * anchor at the top is scrolled into view when a star is added.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
    availableModels: List<ModelInfo>,
    currentModel: String,
    currentProviderSlug: String = "",
    modelsLoading: Boolean,
    onSelect: (modelId: String, providerSlug: String, global: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    ModelPickerSheetWithContext(
        context = LocalContext.current,
        availableModels = availableModels,
        currentModel = currentModel,
        currentProviderSlug = currentProviderSlug,
        modelsLoading = modelsLoading,
        onSelect = onSelect,
        onDismiss = onDismiss,
    )
}

/**
 * Model picker bottom sheet with Favorites support.
 *
 * CR-2/ME-3: ONE filtered, de-duplicated list keyed by "providerSlug|modelId"
 * drives both views. Search filters it once; All Models and grouped views are
 * just two presentations of the same list, so no key can be emitted twice.
 *
 * HI-1: starring never removes a row from its provider group — the star fills
 * in place and the Favorites anchor is scrolled into view as feedback.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheetWithContext(
    context: Context,
    availableModels: List<ModelInfo>,
    currentModel: String,
    currentProviderSlug: String = "",
    modelsLoading: Boolean,
    onSelect: (modelId: String, providerSlug: String, global: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    var global by remember { mutableStateOf(false) }

    // ME-2: the switch is labelled "All Models" (what it controls); the text
    // next to it states the current mode. The choice survives picker opens.
    val viewPrefs = remember(context) {
        context.applicationContext.getSharedPreferences(PICKER_PREFS, Context.MODE_PRIVATE)
    }
    var showAllModels by remember(viewPrefs) {
        mutableStateOf(viewPrefs.getBoolean(KEY_ALL_MODELS, false))
    }
    fun setViewMode(allModels: Boolean) {
        showAllModels = allModels
        viewPrefs.edit().putBoolean(KEY_ALL_MODELS, allModels).apply()
    }

    val favoritesStore = remember(context) { ModelFavoritesStore.getInstance(context.applicationContext) }
    val favorites by favoritesStore.favoritesFlow.collectAsState(initial = emptySet())

    // Single composite key for every LazyList item (CR-2).
    val modelKey = { model: ModelInfo -> FavoriteKeys.forModel(model.providerSlug, model.id) }

    // One filtered list, derived once. Search applies to both views and to
    // favourites, which are marked in place (ME-3).
    val filterText = searchQuery.trim()
    val filteredModels = remember(availableModels, filterText) {
        availableModels.filter { m ->
            m.id.contains(filterText, ignoreCase = true) ||
                m.name.contains(filterText, ignoreCase = true)
        }.distinctBy(modelKey)
    }

    // HI-2/ME-4: migrate legacy keys and prune stale ones only against a real,
    // loaded inventory — an empty list means loading/no data, not "no models".
    LaunchedEffect(availableModels, modelsLoading) {
        if (!modelsLoading && availableModels.isNotEmpty()) {
            favoritesStore.syncInventory(availableModels)
        }
    }

    // HI-1: when a star is added, bring the Favorites anchor back into view.
    val listState = rememberLazyListState()
    var lastFavoriteCount by remember { mutableStateOf(favorites.size) }
    LaunchedEffect(favorites.size) {
        if (favorites.size > lastFavoriteCount) listState.scrollToItem(0)
        lastFavoriteCount = favorites.size
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxHeight(0.85f)
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Select Model",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "Global",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Switch(
                    checked = global,
                    onCheckedChange = { global = it },
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "All Models",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Switch(
                    checked = showAllModels,
                    onCheckedChange = { setViewMode(it) },
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .semantics { contentDescription = "All Models mode" },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = HermesPrimary,
                        checkedTrackColor = HermesPrimary.copy(alpha = 0.5f)
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (showAllModels) "Showing: all models (flat)" else "Showing: grouped by provider",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search models…") },
                leadingIcon = {
                    Icon(Icons.Filled.Search, contentDescription = null)
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    focusedBorderColor = HermesPrimary.copy(alpha = 0.5f),
                    unfocusedBorderColor = Color.Transparent,
                )
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (modelsLoading) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    // Favorites anchor (HI-1). It carries no model rows, so a
                    // starred model can stay in its provider group without any
                    // duplicate LazyColumn key.
                    if (favorites.isNotEmpty()) {
                        item(key = "favorites_header") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    Icons.Filled.Star,
                                    contentDescription = null,
                                    tint = HermesPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Favorites",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = HermesPrimary
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "${favorites.size}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    if (showAllModels) {
                        // Flat, provider-qualified presentation of the one
                        // filtered list; favourites keep their filled star.
                        items(filteredModels, key = modelKey) { model ->
                            ModelPickerItem(
                                model = model,
                                isCurrent = isCurrent(model, currentModel, currentProviderSlug),
                                isFavorite = favorites.contains(modelKey(model)),
                                onSelect = { onSelect(model.id, model.providerSlug, global); onDismiss() },
                                onToggleFavorite = { favoritesStore.toggleFavorite(model.providerSlug, model.id) },
                                showProviderBadge = true
                            )
                        }
                    } else {
                        filteredModels
                            .groupBy { it.provider.ifBlank { "other" } }
                            .toSortedMap()
                            .forEach { (provider, models) ->
                                item(key = "provider_$provider") {
                                    Surface(
                                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                        color = Color.Transparent
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = provider.uppercase(),
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = HermesPrimary
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                text = "${models.size} models",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                                items(models, key = modelKey) { model ->
                                    ModelPickerItem(
                                        model = model,
                                        isCurrent = isCurrent(model, currentModel, currentProviderSlug),
                                        isFavorite = favorites.contains(modelKey(model)),
                                        onSelect = { onSelect(model.id, model.providerSlug, global); onDismiss() },
                                        onToggleFavorite = { favoritesStore.toggleFavorite(model.providerSlug, model.id) }
                                    )
                                }
                            }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

private fun isCurrent(model: ModelInfo, currentModel: String, currentProviderSlug: String): Boolean =
    model.id == currentModel &&
        (currentProviderSlug.isBlank() || model.providerSlug == currentProviderSlug)

/** Individual model item in the picker with favorite toggle. */
@Composable
private fun ModelPickerItem(
    model: ModelInfo,
    isCurrent: Boolean,
    isFavorite: Boolean,
    onSelect: () -> Unit,
    onToggleFavorite: () -> Unit,
    showProviderBadge: Boolean = false,
) {
    Surface(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = if (isCurrent)
            HermesPrimary.copy(alpha = 0.1f)
        else Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (model.isVision) {
                Icon(
                    Icons.Filled.Visibility,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            } else if (model.isFree) {
                Icon(
                    Icons.Filled.LockOpen,
                    contentDescription = "Free",
                    tint = SuccessGreen,
                    modifier = Modifier.size(18.dp)
                )
            } else {
                Box(modifier = Modifier.size(18.dp))
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = model.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (showProviderBadge && model.provider.isNotBlank()) {
                    Text(
                        text = model.provider,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            IconButton(onClick = onToggleFavorite) {
                Icon(
                    if (isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = if (isFavorite) "Unfavorite" else "Favorite",
                    tint = HermesPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }

            if (isCurrent) {
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "Current",
                    tint = HermesPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
