package com.hermes.mobile.data.local

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.hermes.mobile.data.model.ModelInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Key for a favorite: "providerSlug|modelId". Model ids repeat across
 * providers (e.g. a free tier of `gemini` also exists under another
 * provider), so an id-only key aliases two distinct models. Storing the
 * provider makes each starred row globally identifiable. */
object FavoriteKeys {
    const val SEPARATOR = '|'

    fun forModel(providerSlug: String, modelId: String): String =
        "$providerSlug$SEPARATOR$modelId"
}

/** Manages model favorites persistence using SharedPreferences.
 *
 * Entries are stored as an ordered JSON array of `{"k":"providerSlug|id","t":epochMs}`
 * so eviction is oldest-first and deterministic. Legacy id-only entries and
 * truncated JSON are migrated/repaired instead of being wiped. */
class ModelFavoritesStore internal constructor(
    private val prefs: SharedPreferences
) {
    companion object {
        private const val TAG = "ModelFavoritesStore"
        private const val PREFS_NAME = "hermes_model_favorites"
        private const val KEY_FAVORITES = "favorites"
        internal const val MAX_FAVORITES = 50
        // Optional closing quote so a truncated final entry is still salvaged.
        private val QUOTED_KEY = Regex("\"([^\"\\\\]+)\"?")

        @Volatile
        private var INSTANCE: ModelFavoritesStore? = null

        fun getInstance(context: Context): ModelFavoritesStore {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    ModelFavoritesStore(prefs).also { INSTANCE = it }
                }
            }
        }
    }

    private val _favoritesFlow = MutableStateFlow(getFavoritesSet())

    /** Flow of the current favourites (each entry is "providerSlug|modelId"). */
    val favoritesFlow: Flow<Set<String>> = _favoritesFlow

    /** Get current favourites as an ordered Set of composite keys. */
    fun getFavorites(): Set<String> = getFavoritesSet()

    /** Check if a model is favourited for a specific provider. */
    fun isFavorite(providerSlug: String, modelId: String): Boolean =
        getFavoritesSet().contains(FavoriteKeys.forModel(providerSlug, modelId))

    /** Migrate legacy id-only keys and drop keys no longer advertised.
     *  An empty/loading inventory is never used to prune. */
    fun syncInventory(models: List<ModelInfo>) {
        if (models.isEmpty()) return
        val advertised = models.map { FavoriteKeys.forModel(it.providerSlug, it.id) }.toHashSet()
        val current = readState()
        val next = LinkedHashMap<String, Long>()
        var changed = false

        current.forEach { (key, ts) ->
            if (key.contains(FavoriteKeys.SEPARATOR)) {
                if (key in advertised) next[key] = ts else changed = true
            } else {
                // The old id-only star applied to every provider exposing that
                // id; preserve that intent with one composite key per provider.
                val matches = models.filter { it.id == key }
                if (matches.isEmpty()) changed = true
                matches.forEach { model ->
                    val composite = FavoriteKeys.forModel(model.providerSlug, model.id)
                    if (!next.containsKey(composite)) next[composite] = ts
                }
                changed = true
            }
        }
        val capped = capOldest(next)
        if (changed || capped.keys != current.keys) {
            persistState(capped)
            _favoritesFlow.value = capped.keys.toSet()
        }
    }

    /** Toggle a model's favourite status for one provider. */
    fun toggleFavorite(providerSlug: String, modelId: String): Boolean {
        val state = readState()
        val key = FavoriteKeys.forModel(providerSlug, modelId)
        if (state.remove(key) != null) {
            persistState(state)
            _favoritesFlow.value = state.keys.toSet()
            return false
        }
        return addFavorite(providerSlug, modelId)
    }

    /** Add a model to favourites (for one provider). At the cap the request is
     *  refused — no silent eviction of an existing favourite. */
    fun addFavorite(providerSlug: String, modelId: String): Boolean {
        val state = readState()
        val key = FavoriteKeys.forModel(providerSlug, modelId)
        if (state.containsKey(key)) return false
        if (state.size >= MAX_FAVORITES) {
            Log.w(TAG, "Favorite cap ($MAX_FAVORITES) reached; ignoring $key")
            return false
        }
        state[key] = System.currentTimeMillis()
        persistState(state)
        _favoritesFlow.value = state.keys.toSet()
        return true
    }

    /** Remove a model from favourites for one provider. */
    fun removeFavorite(providerSlug: String, modelId: String): Boolean {
        val state = readState()
        val key = FavoriteKeys.forModel(providerSlug, modelId)
        if (state.remove(key) != null) {
            persistState(state)
            _favoritesFlow.value = state.keys.toSet()
            return true
        }
        return false
    }

    /** Clear all favourites. */
    fun clearAll() {
        persistState(emptyMap())
        _favoritesFlow.value = emptySet()
    }

    private fun getFavoritesSet(): Set<String> = readState().keys.toSet()

    private fun readState(): LinkedHashMap<String, Long> {
        val raw = prefs.getString(KEY_FAVORITES, "[]") ?: "[]"
        val state = LinkedHashMap<String, Long>()
        var normalize = false
        try {
            val array = org.json.JSONArray(raw)
            for (i in 0 until array.length()) {
                when (val entry = array.opt(i)) {
                    is String -> {
                        if (entry.isNotBlank()) state[entry] = System.currentTimeMillis()
                        normalize = true // legacy string format → rewrite on read
                    }
                    is org.json.JSONObject -> {
                        val key = entry.optString("k", "").ifBlank { entry.optString("key", "") }
                        if (key.isNotBlank()) {
                            state[key] = entry.optLong("t", System.currentTimeMillis())
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Corrupt favorites JSON; salvaging readable keys", e)
            // A truncated write can still contain valid keys. Recover them
            // rather than treating the whole value as empty.
            QUOTED_KEY.findAll(raw).forEach { match ->
                val key = match.groupValues[1]
                if (key.isNotBlank() && key != "k" && key != "key" && key != "t") {
                    state[key] = System.currentTimeMillis()
                }
            }
            normalize = state.isNotEmpty()
        }
        val capped = capOldest(state)
        if (normalize) persistState(capped)
        return capped
    }

    /** Oldest timestamp first; stable sort keeps insertion order on ties. */
    private fun capOldest(state: LinkedHashMap<String, Long>): LinkedHashMap<String, Long> {
        if (state.size <= MAX_FAVORITES) return state
        val capped = LinkedHashMap<String, Long>()
        state.entries.sortedBy { it.value }.take(MAX_FAVORITES).forEach { capped[it.key] = it.value }
        Log.w(TAG, "Favorites exceeded cap ($MAX_FAVORITES); dropped ${state.size - capped.size} oldest")
        return capped
    }

    private fun persistState(state: Map<String, Long>) {
        try {
            val array = org.json.JSONArray()
            state.forEach { (key, ts) ->
                array.put(org.json.JSONObject().put("k", key).put("t", ts))
            }
            prefs.edit().putString(KEY_FAVORITES, array.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Could not persist favorites", e)
        }
    }
}
