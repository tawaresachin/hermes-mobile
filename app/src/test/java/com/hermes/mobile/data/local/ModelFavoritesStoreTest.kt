package com.hermes.mobile.data.local

import android.content.SharedPreferences
import android.util.Log
import com.hermes.mobile.data.model.ModelInfo
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Focused persistence tests for HI-2/ME-4. SharedPreferences is backed by a
 *  single in-memory string so reads observe the last write. */
class ModelFavoritesStoreTest {

    private class TestPrefs(initial: String? = null) {
        var stored: String? = initial
            private set
        val editor: SharedPreferences.Editor = mockk(relaxed = true)
        val prefs: SharedPreferences = mockk(relaxed = true)

        init {
            every { editor.putString(any(), any()) } answers {
                stored = secondArg()
                editor
            }
            every { prefs.getString(any(), any()) } answers { stored ?: secondArg<String>() }
            every { prefs.edit() } returns editor
        }
    }

    @Before
    fun setUpLog() {
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
    }

    @After
    fun tearDownLog() {
        unmockkStatic(Log::class)
    }

    @Test
    fun migratesLegacyIdOnlyKeysToEveryAdvertisedProvider() {
        val tp = TestPrefs("""["agnes-3.0-flash"]""")
        val store = ModelFavoritesStore(tp.prefs)
        assertEquals(setOf("agnes-3.0-flash"), store.getFavorites())

        store.syncInventory(
            listOf(
                ModelInfo(id = "agnes-3.0-flash", name = "A", provider = "Agnes Paid", providerSlug = "agnes"),
                ModelInfo(id = "agnes-3.0-flash", name = "A", provider = "FreeLLM", providerSlug = "free"),
                ModelInfo(id = "other", name = "O", provider = "Other", providerSlug = "other"),
            )
        )

        assertEquals(setOf("agnes|agnes-3.0-flash", "free|agnes-3.0-flash"), store.getFavorites())
        assertTrue(store.isFavorite("agnes", "agnes-3.0-flash"))
        assertFalse(store.isFavorite("other", "agnes-3.0-flash"))
    }

    @Test
    fun prunesStaleKeysButNeverPrunesOnEmptyInventory() {
        val tp = TestPrefs("""[{"k":"a|old","t":1},{"k":"b|keep","t":2}]""")
        val store = ModelFavoritesStore(tp.prefs)

        store.syncInventory(listOf(ModelInfo(id = "keep", name = "K", provider = "B", providerSlug = "b")))
        assertEquals(setOf("b|keep"), store.getFavorites())

        store.syncInventory(emptyList())
        assertEquals(setOf("b|keep"), store.getFavorites())
    }

    @Test
    fun capRefusesNewFavoriteWithoutEvictingExistingOnes() {
        val tp = TestPrefs()
        val store = ModelFavoritesStore(tp.prefs)
        repeat(ModelFavoritesStore.MAX_FAVORITES) { i ->
            assertTrue(store.addFavorite("p$i", "m$i"))
        }
        assertEquals(ModelFavoritesStore.MAX_FAVORITES, store.getFavorites().size)

        assertFalse(store.addFavorite("overflow", "m"))
        assertFalse(store.toggleFavorite("overflow", "m"))
        assertEquals(ModelFavoritesStore.MAX_FAVORITES, store.getFavorites().size)
        assertTrue(store.getFavorites().contains("p0|m0"))
        assertEquals("p0|m0", store.getFavorites().first())
    }

    @Test
    fun corruptJsonIsSalvagedAndRepairedWithoutWipingUnreadableData() {
        val truncated = TestPrefs("""["a|x","b|y""")
        val repaired = ModelFavoritesStore(truncated.prefs)
        assertEquals(setOf("a|x", "b|y"), repaired.getFavorites())
        assertEquals(2, JSONArray(truncated.stored!!).length())

        val unreadable = TestPrefs("not json")
        val untouched = ModelFavoritesStore(unreadable.prefs)
        assertTrue(untouched.getFavorites().isEmpty())
        assertEquals("not json", unreadable.stored)
    }

    @Test
    fun storageIsOrderedAndTimestamped() {
        val tp = TestPrefs()
        val store = ModelFavoritesStore(tp.prefs)
        store.addFavorite("p1", "m1")
        store.addFavorite("p2", "m2")

        val array = JSONArray(tp.stored!!)
        assertEquals(2, array.length())
        assertEquals("p1|m1", array.getJSONObject(0).getString("k"))
        assertEquals("p2|m2", array.getJSONObject(1).getString("k"))
        assertTrue(array.getJSONObject(0).getLong("t") > 0)
    }
}
