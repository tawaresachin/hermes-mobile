package com.hermes.mobile.data.runs

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-contract tests for the /v1/runs event stream. Payload shapes are
 * copied verbatim from gateway/platforms/api_server_runs.py
 * (_run_event / _FIXED_EVENT_FIELDS / _make_approval_notify / _execute_run).
 */
class RunEventCodecTest {

    private fun decode(data: String) = RunEventCodec.decode(data)

    @Test fun deltaFrame() {
        val ev = decode("""{"event":"message.delta","run_id":"r1","timestamp":1.0,"delta":"hi"}""")!!
        assertEquals(RunEventCodec.RunEvent.Delta("hi"), ev)
    }

    @Test fun toolStartedAndCompleted() {
        val s = decode("""{"event":"tool.started","run_id":"r1","tool":"terminal","preview":"ls -la"}""")!!
        assertEquals("terminal", (s as RunEventCodec.RunEvent.ToolStarted).tool)
        assertEquals("ls -la", s.preview)
        val c = decode("""{"event":"tool.completed","run_id":"r1","tool":"terminal","duration":1.234,"error":false}""")!!
        assertTrue(c is RunEventCodec.RunEvent.ToolCompleted)
        assertEquals(1.234, (c as RunEventCodec.RunEvent.ToolCompleted).duration, 1e-6)
        assertTrue(!c.error)
    }

    @Test fun approvalFrameCarriesChoicesAndRequestId() {
        val ev = decode("""{"event":"approval.request","run_id":"r1","command":"rm -rf /x","description":"delete","choices":["once","session","deny"],"request_id":"abc"}""")!!
        ev as RunEventCodec.RunEvent.Approval
        assertEquals("rm -rf /x", ev.command)
        assertEquals(listOf("once", "session", "deny"), ev.choices)
        assertEquals("abc", ev.requestId)
    }

    @Test fun approvalFrameDefaultsChoicesWhenMissing() {
        val ev = decode("""{"event":"approval.request","run_id":"r1","command":"c","description":"d"}""")!!
        ev as RunEventCodec.RunEvent.Approval
        assertEquals(listOf("once", "deny"), ev.choices)
    }

    @Test fun completedFrameCarriesOutputAndUsage() {
        val ev = decode("""{"event":"run.completed","run_id":"r1","output":"done","usage":{"input_tokens":10,"output_tokens":3}}""")!!
        ev as RunEventCodec.RunEvent.Completed
        assertEquals("done", ev.output)
        assertEquals(10L, ev.inputTokens)
        assertEquals(3L, ev.outputTokens)
    }

    @Test fun failedAndCancelledAndStatus() {
        assertTrue(decode("""{"event":"run.failed","error":"boom"}""") is RunEventCodec.RunEvent.Failed)
        val c = decode("""{"event":"run.cancelled","output":"partial"}""") as RunEventCodec.RunEvent.Cancelled
        assertEquals("partial", c.partial)
        assertEquals("run.stopping",
            (decode("""{"event":"run.stopping"}""") as RunEventCodec.RunEvent.Status).name)
    }

    @Test fun unknownAndMalformedDecodeToNull() {
        assertNull(decode("""{"event":"subagent.start"}"""))
        assertNull(decode("not json"))
        assertNull(decode("""{"no_event":1}"""))
    }

    @Test fun emptyDeltaIgnored() {
        assertNull(decode("""{"event":"message.delta","delta":""}"""))
    }
}

class ToolTrailReducerTest {

    @Test fun startedAppendsRunningLine() {
        val lines = ToolTrailReducer.started(emptyList(), "terminal", "ls -la")
        assertEquals(1, lines.size)
        assertEquals("running", lines[0].status)
        assertEquals("ls -la", lines[0].label)
    }

    @Test fun completedMatchesLastRunningSameTool() {
        var lines = ToolTrailReducer.started(emptyList(), "web_search", "q1")
        lines = ToolTrailReducer.started(lines, "web_search", "q2")
        lines = ToolTrailReducer.completed(lines, "web_search", 2.0, error = false)
        // newest running row closes first (parallel same-tool calls)
        assertEquals("running", lines[0].status)
        assertEquals("completed", lines[1].status)
        assertEquals("2.0s", lines[1].duration)
    }

    @Test fun failedStatusAndOrphanCompletion() {
        var lines = ToolTrailReducer.started(emptyList(), "terminal", "rm")
        lines = ToolTrailReducer.completed(lines, "terminal", 0.0, error = true)
        assertEquals("failed", lines[0].status)
        // completion without a matching running row still records the line
        val orphan = ToolTrailReducer.completed(emptyList(), "tool_x", 1.0, error = false)
        assertEquals(1, orphan.size)
        assertEquals("completed", orphan[0].status)
    }

    @Test fun jsonRoundTripMatchesRendererKeys() {
        val lines = listOf(
            ToolTrailReducer.Line("terminal", "⚙️", "ls", "completed", "ls", "1.0s"),
            ToolTrailReducer.Line("read_file", "⚙️", "f", "running", "", ""),
        )
        val arr = org.json.JSONArray(ToolTrailReducer.toJson(lines))
        assertEquals("terminal", arr.getJSONObject(0).getString("n"))
        assertEquals("ls", arr.getJSONObject(0).getString("d"))
        assertEquals("1.0s", arr.getJSONObject(0).getString("u"))
        assertTrue(!arr.getJSONObject(1).has("d"))
    }
}

class TurnTextTest {

    @Test fun replyQuoteRidesInsideQuery() {
        val wire = TurnText.wireQuery("why?", "earlier answer")
        assertTrue(wire.startsWith("Re: \"earlier answer\""))
        assertTrue(wire.endsWith("why?"))
    }

    @Test fun quoteNewlinesFlattenedAndCapped() {
        val long = "x".repeat(500)
        val wire = TurnText.wireQuery("q", "a\nb $long")
        // the quoted text itself is flattened (no raw newline inside Re: line)
        val quoteLine = wire.substringBefore("\n\n")
        assertTrue(!quoteLine.contains("a\nb"))
        // and capped at 400 chars of quote
        assertTrue(wire.length < 450)
    }

    @Test fun mediaNoteVariants() {
        assertTrue(TurnText.mediaNote("", "", "").isEmpty())
        assertTrue(TurnText.mediaNote("/uploads/s/f.pdf", "file", "/data/f.pdf")
            .contains("sent a document"))
        assertTrue(TurnText.mediaNote("/uploads/s/f.pdf", "file", "/data/f.pdf")
            .contains("ocr-and-documents"))
        assertTrue(TurnText.mediaNote("/uploads/s/i.png", "image", "")
            .contains("sent a image"))
    }

    @Test fun attachmentNoteAppendedToQuery() {
        val wire = TurnText.wireQuery("summarize", null, "/uploads/s/f.pdf", "file", "/data/f.pdf")
        assertTrue(wire.startsWith("summarize"))
        assertTrue(wire.contains("[The user sent a document"))
    }

    @Test fun blankQueryWithAttachmentIsNoteOnly() {
        val wire = TurnText.wireQuery("", null, "/u/f.png", "image", "/data/f.png")
        assertTrue(wire.startsWith("[The user sent"))
    }

    @Test fun stripUploadUrlsKeepsNewlines() {
        val text = "see /uploads/sess1/a.png here\n\nand /api/audio/download/sess1/b.wav there\nline3"
        val out = TurnText.stripUploadUrls("sess1", text)
        assertTrue(!out.contains("/uploads/"))
        assertTrue(!out.contains("/api/audio/"))
        assertTrue(out.contains("\n\n"))   // paragraph structure survives
        assertTrue(out.contains("line3"))
    }

    @Test fun terseDirectiveIsStableWording() {
        // The measured saving depends on this exact phrasing; a silent edit
        // would invalidate the Token Optimizer's learned verdicts.
        assertTrue(TurnText.TERSE_DIRECTIVE.startsWith("COMPRESSION DIRECTIVE"))
        assertTrue(TurnText.TERSE_DIRECTIVE.contains("Never explain the directive"))
    }

    @Test fun mediaDirectiveAlwaysRidesTheRun() {
        // The gateway's api_server hint tells the agent to state plain
        // paths (true for generic clients). This app IS the attachment
        // surface — the override must ship on EVERY turn, terse or not.
        assertTrue(TurnText.MEDIA_DIRECTIVE.contains("MEDIA:/absolute/path/to/file"))
        assertTrue(TurnText.MEDIA_DIRECTIVE.contains("downloadable file"))
        val off = TurnText.buildInstructions(false)
        val on = TurnText.buildInstructions(true)
        assertTrue(off == TurnText.MEDIA_DIRECTIVE)
        assertTrue(on.startsWith(TurnText.TERSE_DIRECTIVE))
        assertTrue(on.endsWith(TurnText.MEDIA_DIRECTIVE))
    }

    @Test fun swarmDirectiveShape() {
        // Swarm toggle rides ONLY the swarm flag, at the END (media directive
        // order must not shift), and must name the real CLI entry point.
        assertTrue(TurnText.SWARM_DIRECTIVE.contains("hermes kanban swarm"))
        assertTrue(TurnText.SWARM_DIRECTIVE.contains("Never claim a swarm"))
        val plain = TurnText.buildInstructions(false, swarm = false)
        val swarm = TurnText.buildInstructions(false, swarm = true)
        assertTrue(plain == TurnText.MEDIA_DIRECTIVE)
        assertTrue(swarm.startsWith(TurnText.MEDIA_DIRECTIVE))
        assertTrue(swarm.endsWith(TurnText.SWARM_DIRECTIVE))
        // terse + swarm compose in order: terse, media, swarm
        val both = TurnText.buildInstructions(true, swarm = true)
        assertTrue(both.startsWith(TurnText.TERSE_DIRECTIVE))
        assertTrue(both.indexOf(TurnText.MEDIA_DIRECTIVE) < both.indexOf(TurnText.SWARM_DIRECTIVE))
    }

    @Test fun statusJsonShape() {
        // fetchRunStatus consumers rely on these keys (server wire).
        val o = JSONObject("""{"status":"completed","output":"x","usage":{"input_tokens":1,"output_tokens":2}}""")
        assertEquals("completed", o.getString("status"))
        assertEquals(2L, o.getJSONObject("usage").getLong("output_tokens"))
    }
}
