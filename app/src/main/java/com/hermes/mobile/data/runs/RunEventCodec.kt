package com.hermes.mobile.data.runs

import org.json.JSONObject

/**
 * Pure decoder for the gateway's /v1/runs/{id}/events SSE frames.
 *
 * Wire shape (verified against gateway/platforms/api_server_runs.py):
 * every frame is a bare `data:` JSON object whose BODY carries the event
 * name in the "event" key (no SSE `event:` line). Comments (": keepalive",
 * ": stream closed") never reach the decoder — OkHttp SSE drops them.
 *
 * Kept dependency-free (org.json only) so it is JVM-unit-testable.
 */
object RunEventCodec {

    sealed interface RunEvent {
        /** Incremental assistant text. */
        data class Delta(val text: String) : RunEvent
        /** Tool started: name + one-line preview. */
        data class ToolStarted(val tool: String, val preview: String) : RunEvent
        /** Tool finished; error=true marks failure. duration in seconds. */
        data class ToolCompleted(val tool: String, val duration: Double, val error: Boolean) : RunEvent
        /** The run needs a human approval decision before continuing. */
        data class Approval(
            val command: String,
            val description: String,
            val choices: List<String>,
            val requestId: String,
        ) : RunEvent
        /** Terminal success. output = full text; usage = SESSION totals. */
        data class Completed(val output: String, val inputTokens: Long, val outputTokens: Long) : RunEvent
        data class Failed(val error: String) : RunEvent
        data class Cancelled(val partial: String) : RunEvent
        /** Non-terminal status transitions the UI mirrors (stopping/steered). */
        data class Status(val name: String) : RunEvent
    }

    /** Decode one SSE data payload. Returns null for frames we ignore
     * (unknown events, malformed JSON) — never throws. */
    fun decode(data: String): RunEvent? {
        val o = try { JSONObject(data) } catch (_: Exception) { return null }
        return when (o.optString("event", "")) {
            "message.delta" -> {
                val d = o.optString("delta", "")
                if (d.isEmpty()) null else RunEvent.Delta(d)
            }
            "tool.started" -> RunEvent.ToolStarted(
                o.optString("tool", ""), o.optString("preview", ""))
            "tool.completed" -> RunEvent.ToolCompleted(
                o.optString("tool", ""), o.optDouble("duration", 0.0), o.optBoolean("error", false))
            "approval.request" -> RunEvent.Approval(
                command = o.optString("command", ""),
                description = o.optString("description", ""),
                choices = o.optJSONArray("choices")?.let { arr ->
                    (0 until arr.length()).map { arr.optString(it) }
                } ?: listOf("once", "deny"),
                requestId = o.optString("request_id", ""))
            "run.completed" -> {
                val u = o.optJSONObject("usage")
                RunEvent.Completed(
                    output = o.optString("output", ""),
                    inputTokens = u?.optLong("input_tokens", 0L) ?: 0L,
                    outputTokens = u?.optLong("output_tokens", 0L) ?: 0L)
            }
            "run.failed" -> RunEvent.Failed(o.optString("error", "Run failed"))
            "run.cancelled" -> RunEvent.Cancelled(o.optString("output", ""))
            "run.stopping", "run.steered", "approval.responded" -> RunEvent.Status(o.optString("event"))
            else -> null
        }
    }
}

/**
 * Fold one decoded event into the tool-chrome line list persisted with the
 * bubble. Line shape matches the existing renderer: {n,e,l,s} (+d preview,
 * +u duration) — old rows without the new keys still render.
 */
object ToolTrailReducer {
    data class Line(val name: String, val emoji: String, val label: String, val status: String,
                    val preview: String = "", val duration: String = "")

    fun started(lines: List<Line>, tool: String, preview: String): List<Line> {
        val label = preview.ifBlank { tool }
        return lines + Line(tool, "⚙️", label, "running", preview)
    }

    fun completed(lines: List<Line>, tool: String, duration: Double, error: Boolean): List<Line> {
        val idx = lines.indexOfLast { it.name == tool && it.status == "running" }
        val status = if (error) "failed" else "completed"
        val dur = if (duration > 0) String.format("%.1fs", duration) else ""
        return if (idx < 0) lines + Line(tool, "⚙️", tool, status, "", dur)
        else lines.toMutableList().also { it[idx] = it[idx].copy(status = status, duration = dur) }
    }

    /** Serialize for the message.toolActivity column (same JSON array shape
     * the persisted renderer already parses). */
    fun toJson(lines: List<Line>): String {
        val arr = org.json.JSONArray()
        lines.forEach {
            arr.put(JSONObject().apply {
                put("n", it.name); put("e", it.emoji); put("l", it.label); put("s", it.status)
                if (it.preview.isNotBlank()) put("d", it.preview)
                if (it.duration.isNotBlank()) put("u", it.duration)
            })
        }
        return arr.toString()
    }
}
