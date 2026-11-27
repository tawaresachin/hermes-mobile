package com.hermes.mobile.data.runs

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RunTranscriptSettleTest {

    private fun msg(role: String, ts: Double, text: String) = JSONObject()
        .put("role", role)
        .put("timestamp", ts)
        .put("content", text)

    @Test
    fun `accepts assistant persisted after the turn started (epoch seconds)`() {
        val startMs = 1_795_428_000_000L
        val msgs = listOf(
            msg("assistant", 1_795_427_900.0, "old reply"),
            msg("assistant", 1_795_428_060.0, "this reply"),
        )
        assertEquals("this reply", latestFinalAssistantSince(msgs, startMs)?.optString("content"))
    }

    @Test
    fun `rejects the previous turn's reply`() {
        val startMs = 1_795_428_000_000L
        val msgs = listOf(msg("assistant", 1_795_427_900.0, "old reply"))
        assertNull(latestFinalAssistantSince(msgs, startMs))
    }

    @Test
    fun `accepts millisecond timestamps too`() {
        val startMs = 1_795_428_000_000L
        val msgs = listOf(msg("assistant", 1_795_428_060_000.0, "millis reply"))
        assertEquals("millis reply", latestFinalAssistantSince(msgs, startMs)?.optString("content"))
    }
}
