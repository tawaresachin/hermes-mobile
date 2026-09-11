package com.hermes.mobile.ui.markdown

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs the REAL stored api_server replies (captured verbatim from the
 * gateway state.db on 2026-09-11 — the messages that rendered as raw
 * box-drawing noise in v0.0.26) through parseTables and asserts the
 * invariants the chat bubble depends on:
 *
 * 1. No table ever contains a border/rule row (a row whose cells are all
 *    dashes/pipes/plus/blank — that is ─│─ noise leaking into the grid).
 * 2. The prose never still contains a MISSED table: a line carrying a
 *    column delimiter followed by a rule line. (A sentence mentioning │
 *    inline is fine — only header+rule SHAPES must be gone.)
 */
class RealStoredRepliesTableTest {

    private val ruleish = Regex("""^[\s|+\-:]+$""")
    private fun isRule(s: String) =
        s.count { it == '-' } >= 3 && s.matches(ruleish)

    private fun missedTable(text: String): Boolean {
        val lines = text.split("\n")
        var open = false
        for (i in 0 until lines.lastIndex) {
            val cur = lines[i]
            if (cur.trim().startsWith("```")) { open = !open; continue }
            if (open) continue
            val nxt = lines[i + 1]
            val hasDelim = cur.contains('│') || cur.contains('|')
            if (hasDelim && (nxt.contains('─') || isRule(nxt)) && nxt.isNotBlank()) {
                if (nxt.count { it == '─' } + nxt.count { it == '-' } >= 3) return true
            }
        }
        return false
    }

    @Test fun `real stored replies never render raw table shapes`() {
        val raw = javaClass.classLoader!!
            .getResourceAsStream("real_stored_replies.txt")!!
            .readBytes().toString(Charsets.UTF_8)
        val replies = raw.split("\n<<<SPLIT>>>\n").filter { it.isNotBlank() }
        assertTrue("fixture missing", replies.isNotEmpty())
        val parsedFlags = mutableListOf<Boolean>()
        for ((idx, reply) in replies.withIndex()) {
            val r = parseTables(reply)
            if (r == null) {
                // No tables claimed: any header+rule shape must live inside a
                // ``` fence (genuine code examples stay literal by design).
                var open = false
                var prevOutsidePipeHeader = false
                for (l in reply.split("\n")) {
                    if (l.trim().startsWith("```")) { open = !open; prevOutsidePipeHeader = false; continue }
                    if (open) continue
                    val isRuleHere = isRule(l)
                    if (prevOutsidePipeHeader && isRuleHere)
                        throw AssertionError("reply $idx: unclaimed table shape left raw")
                    prevOutsidePipeHeader = l.contains('|') && !isRuleHere
                }
                parsedFlags.add(false)
                continue
            }
            parsedFlags.add(true)
            for ((ti, tab) in r.tables.withIndex()) {
                for ((ri, row) in tab.withIndex()) {
                    val allRule = row.all { it.isBlank() || isRule(it) }
                    assertTrue("reply $idx table $ti row $ri is border noise: $row", !allRule)
                }
            }
            assertTrue("reply $idx: prose still holds a raw table shape", !missedTable(r.prose))
        }
        // The fixture set is table-heavy by selection — most must be claimed.
        val parsed = parsedFlags.count { it }
        assertTrue("expected most stored replies to parse, got $parsed/${replies.size}", parsed * 2 >= replies.size)
    }
}
