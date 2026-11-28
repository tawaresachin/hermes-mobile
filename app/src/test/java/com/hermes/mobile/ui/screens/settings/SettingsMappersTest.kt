package com.hermes.mobile.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

/** JVM tests for the pure JSON → immutable model mappers used by Settings. */
class SettingsMappersTest {

    @Test
    fun capabilityItemsTreatEmptyArrayAsSuccessAndSkipNamelessRows() {
        val empty = JSONObject().put("ok", true).put("plugins", org.json.JSONArray())
        assertTrue(parseCapabilityItems(empty, "plugins").isEmpty())

        val body = JSONObject().put("ok", true).put("plugins", org.json.JSONArray()
            .put(JSONObject().put("description", "no name"))
            .put(JSONObject().put("name", "real").put("description", "ok")))
        assertEquals(listOf("real"), parseCapabilityItems(body, "plugins").map { it.name })
    }

    @Test
    fun capabilityItemsMapFallbackFieldsAndReadFailures() {
        val body = JSONObject().put("ok", false).put("plugins", org.json.JSONArray()
            .put(JSONObject().put("name", "ignored")))
        assertTrue(parseCapabilityItems(body, "plugins").isEmpty())

        val plugin = JSONObject().put("ok", true).put("plugins", org.json.JSONArray()
            .put(JSONObject()
                .put("name", "p1")
                .put("command", "fallback-desc")
                .put("transport", "stdio")
                .put("enabled", false)
                .put("category", "tools")
                .put("essential", true)))
            .let { parseCapabilityItems(it, "plugins").single() }
        assertEquals("fallback-desc", plugin.description)
        assertEquals("stdio", plugin.detail)
        assertEquals(false, plugin.enabled)
        assertEquals("tools", plugin.category)
        assertTrue(plugin.essential)

        val withoutEnabled = JSONObject().put("ok", true).put("plugins", org.json.JSONArray()
            .put(JSONObject().put("name", "p2").put("path", "/tmp/p2")))
            .let { parseCapabilityItems(it, "plugins").single() }
        assertNull(withoutEnabled.enabled)
        assertEquals("/tmp/p2", withoutEnabled.detail)
    }

    @Test
    fun agentProfilesRequireOkAndSkipNamelessRows() {
        assertNull(parseActiveAgentProfile(JSONObject().put("ok", false)))
        val failed = JSONObject().put("ok", true)
            .put("profiles", org.json.JSONArray().put(JSONObject().put("display_name", "no name")))
        assertTrue(parseAgentProfiles(failed).isEmpty())

        val body = JSONObject().put("ok", true).put("profiles", org.json.JSONArray()
            .put(JSONObject().put("name", "bot").put("display_name", "My Bot")
                .put("model", "m").put("provider", "p")
                .put("skill_count", 3).put("has_env", true).put("is_default", true)))
        val profile = parseAgentProfiles(body).single()
        assertEquals("bot", profile.name)
        assertEquals("My Bot", profile.displayName)
        assertEquals("m", profile.model)
        assertEquals("p", profile.provider)
        assertEquals(3, profile.skillCount)
        assertTrue(profile.hasEnv)
        assertTrue(profile.isDefault)

        val fallback = JSONObject().put("ok", true).put("profiles", org.json.JSONArray()
            .put(JSONObject().put("name", "solo")))
            .let { parseAgentProfiles(it).single() }
        assertEquals("solo", fallback.displayName)
        assertEquals(0, fallback.skillCount)
        assertFalse(fallback.hasEnv)
    }

    @Test
    fun activeProfileUsesDisplayNameWithNameFallback() {
        val body = JSONObject().put("ok", true)
            .put("profile", JSONObject().put("name", "default").put("description", "desc"))
        val profile = parseActiveAgentProfile(body)!!
        assertEquals("default", profile.displayName)
        assertEquals("desc", profile.description)
    }

    @Test
    fun learningGraphMapsHostPayloadAndKeepsMissingSectionsEmpty() {
        val body = JSONObject().put("ok", true)
            .put("nodes", org.json.JSONArray()
                .put(JSONObject().put("id", "skill-a").put("label", "Skill A")
                    .put("kind", "skill").put("category", "tools").put("useCount", 4))
                .put(JSONObject().put("id", "")))
            .put("edges", org.json.JSONArray()
                .put(JSONObject().put("source", "skill-a").put("target", "memory:user:0:abc")))
            .put("clusters", org.json.JSONArray().put(JSONObject().put("category", "tools").put("count", 1)))
            .put("stats", JSONObject().put("learned_skills", 1).put("memory_nodes", 2))

        val graph = parseLearningGraph(body)
        assertEquals(1, graph.nodes.size)
        assertEquals("Skill A", graph.nodes.single().label)
        assertEquals(4, graph.nodes.single().useCount)
        assertEquals("memory:user:0:abc", graph.edges.single().target)
        assertEquals("tools", graph.clusters.single().category)
        assertEquals("2", graph.stats["memory_nodes"])

        val empty = parseLearningGraph(JSONObject().put("ok", true))
        assertTrue(empty.nodes.isEmpty())
        assertTrue(empty.edges.isEmpty())
        assertTrue(empty.stats.isEmpty())
    }
}
