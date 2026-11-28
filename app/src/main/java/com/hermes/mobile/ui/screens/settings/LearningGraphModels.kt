package com.hermes.mobile.ui.screens.settings

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject

enum class LearningGraphStatus { IDLE, LOADED, FAILED }

@Immutable
data class LearningGraphNode(
    val id: String,
    val label: String,
    val kind: String,
    val category: String,
    val detail: String,
    val useCount: Int,
)

@Immutable
data class LearningGraphEdge(val source: String, val target: String)

@Immutable
data class LearningGraphCluster(val category: String, val count: Int)

@Immutable
data class LearningGraphData(
    val nodes: List<LearningGraphNode> = emptyList(),
    val edges: List<LearningGraphEdge> = emptyList(),
    val clusters: List<LearningGraphCluster> = emptyList(),
    val stats: Map<String, String> = emptyMap(),
)

@Immutable
data class LearningGraphState(
    val serverKey: String = "",
    val profile: String? = null,
    val loading: Boolean = false,
    val status: LearningGraphStatus = LearningGraphStatus.IDLE,
    val error: String? = null,
    val nodes: List<LearningGraphNode> = emptyList(),
    val edges: List<LearningGraphEdge> = emptyList(),
    val clusters: List<LearningGraphCluster> = emptyList(),
    val stats: Map<String, String> = emptyMap(),
)

/** Parse only the read-only graph payload; absent arrays stay empty, never invented. */
internal fun parseLearningGraph(body: JSONObject): LearningGraphData {
    fun array(name: String): List<JSONObject> {
        val values = body.optJSONArray(name) ?: return emptyList()
        return (0 until values.length()).mapNotNull { values.optJSONObject(it) }
    }
    val nodes = array("nodes").map { o ->
        val source = o.optString("memorySource", "")
        LearningGraphNode(
            id = o.optString("id", ""),
            label = o.optString("label", "").ifBlank { o.optString("id", "") },
            kind = o.optString("kind", "skill"),
            category = o.optString("category", ""),
            detail = if (source.isNotBlank()) source else o.optString("state", ""),
            useCount = o.optInt("useCount", 0),
        )
    }.filter { it.id.isNotBlank() }
    val edges = array("edges").map {
        LearningGraphEdge(it.optString("source", ""), it.optString("target", ""))
    }.filter { it.source.isNotBlank() && it.target.isNotBlank() }
    val clusters = array("clusters").map {
        LearningGraphCluster(it.optString("category", ""), it.optInt("count", 0))
    }.filter { it.category.isNotBlank() }
    val stats = body.optJSONObject("stats")?.let { s ->
        s.keys().asSequence().associateWith { key -> s.opt(key)?.toString().orEmpty() }
            .filterValues { it.isNotBlank() }
    }.orEmpty()
    return LearningGraphData(nodes, edges, clusters, stats)
}
