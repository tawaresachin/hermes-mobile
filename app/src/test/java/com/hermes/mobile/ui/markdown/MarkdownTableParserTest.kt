package com.hermes.mobile.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests pinning the table-parser fixes (columns, prose, false
 * positives, code fences, HTML tables, markdown-in-cells). */
class MarkdownTableParserTest {

    @Test fun `null when there is no table`() {
        assertNull(parseTables("just prose with A|B pipes and no separator"))
        assertNull(parseTables(""))
    }

    @Test fun `simple table parses rows with header`() {
        val md = """
            | Name | Qty |
            | ---- | --- |
            | Foo  | 2   |
            | Bar  | 7   |
        """.trimIndent()
        val t = parseTables(md)
        assertNotNull(t)
        val rows = t!!.tables.single()
        assertEquals(listOf("Name", "Qty"), rows[0])
        assertEquals(listOf("Foo", "2"), rows[1])
        assertEquals(3, rows.size)
        assertEquals("", t.prose)
    }

    @Test fun `empty middle cell keeps its column position`() {
        val t = parseTables("| A | B | C |\n|---|---|---|\n| x |   | z |")
        assertEquals(listOf("A", "B", "C"), t!!.tables[0][0])
        assertEquals(listOf("x", "", "z"), t.tables[0][1])
    }

    @Test fun `ragged rows are padded to equal columns`() {
        val t = parseTables("| H1 | H2 | H3 |\n|---|---|---|\n| a | b |\n| c | d | e |")
        val rows = t!!.tables[0]
        assertEquals(listOf("a", "b", ""), rows[1])
        assertEquals(listOf("c", "d", "e"), rows[2])
    }

    @Test fun `surrounding prose is preserved separately`() {
        val md = "Here's the comparison:\n\n| Name | Qty |\n| ---- | --- |\n| Foo | 2 |\n\nOverall Foo wins."
        val t = parseTables(md)
        assertNotNull(t)
        assertEquals(2, t!!.tables[0].size)
        assertEquals("Here's the comparison:\n\nOverall Foo wins.", t.prose)
    }

    @Test fun `two tables both parse in document order`() {
        val md = "Before:\n| A | B |\n|---|---|\n| 1 | 2 |\nMiddle note\n| C | D |\n|---|---|\n| 3 | 4 |\nAfter."
        val t = parseTables(md)
        assertEquals(2, t!!.tables.size)
        assertEquals(listOf("A", "B"), t.tables[0][0])
        assertEquals(listOf("C", "D"), t.tables[1][0])
        assertEquals("Before:\nMiddle note\nAfter.", t.prose)
    }

    @Test fun `colon alignment markers in separator are fine`() {
        val t = parseTables("| L | R |\n|:--|--:|\n| a | b |")
        assertEquals(listOf("a", "b"), t!!.tables[0][1])
    }

    @Test fun `single pipe line without separator is not a table`() {
        assertNull(parseTables("use A|B notation here\nmore prose"))
    }

    @Test fun `header-only markdown table is still a table`() {
        val t = parseTables("| A | B |\n|---|---|")
        assertEquals(1, t!!.tables[0].size)
    }

    @Test fun `table inside a code fence of real code stays literal text`() {
        // Mixed content = genuine code example -> no lifting
        val md = "```\nif x:\n    y = a|b\n```\nDone."
        assertNull(parseTables(md))
    }

    @Test fun `pure table wrapped in a fence is lifted (models fence tables)`() {
        val md = "Summary:\n```\n| A | B |\n|---|---|\n| 1 | 2 |\n```\nEnd."
        val t = parseTables(md)
        assertNotNull(t)
        assertEquals(1, t!!.tables.size)
        assertEquals(listOf("A", "B"), t.tables[0][0])
        assertEquals(listOf("1", "2"), t.tables[0][1])
        assertEquals("Summary:\nEnd.", t.prose)   // fence markers consumed too
    }

    @Test fun `regression real stored reply - padded fenced table without edge pipes`() {
        // Verbatim shape of a 2026-09-11 mobile session reply that rendered
        // as raw pipes in v0.0.26: fence-wrapped, space-padded, no outer pipes.
        val stored = "Provider Status Summary:\n\n```\nProvider           | Status      | Notes\n" +
            "-------------------|-------------|----------------------\n" +
            "copilot            | Works       | Full access\n" +
            "zen                | Fails (401) | Auth error\n```\n\nBug found: something else."
        val t = parseTables(stored)
        assertNotNull(t)
        val rows = t!!.tables.single()
        assertEquals(listOf("Provider", "Status", "Notes"), rows[0])
        assertEquals(listOf("copilot", "Works", "Full access"), rows[1])
        assertEquals(listOf("zen", "Fails (401)", "Auth error"), rows[2])
        assertEquals(3, rows.size)
        assertEquals("Provider Status Summary:\n\nBug found: something else.", t.prose)
    }

    @Test fun `unicode box-drawing table (rich CLI) parses after normalization`() {
        // Shape of Hermes CLI output the mobile app kept rendering raw in
        // v0.0.26: │ separators, ─ rule, ┼ junctions, padded columns.
        val box = "Status:\n\n" +
            "Provider          │ Model        │ Status\n" +
            "──────────────────┼──────────────┼─────────\n" +
            "copilot           │ gpt-4.1      │ OK\n" +
            "zen               │ glm-5.3      │ 401\n\n" +
            "Done."
        val t = parseTables(box)
        assertNotNull(t)
        val rows = t!!.tables.single()
        assertEquals(listOf("Provider", "Model", "Status"), rows[0])
        assertEquals(listOf("copilot", "gpt-4.1", "OK"), rows[1])
        assertEquals(listOf("zen", "glm-5.3", "401"), rows[2])
        assertEquals(3, rows.size)
        assertEquals("Status:\n\nDone.", t.prose)
    }

    @Test fun `bordered box table drops rule and border lines`() {
        val box = "┌──────────┬────────┐\n│ A        │ B      │\n├──────────┼────────┤\n│ 1        │ 2      │\n└──────────┴────────┘"
        val t = parseTables(box)
        assertNotNull(t)
        val rows = t!!.tables.single()
        assertEquals(2, rows.size)  // header + 1 data row, no border noise
        assertEquals(listOf("A", "B"), rows[0])
        assertEquals(listOf("1", "2"), rows[1])
        assertEquals("", t.prose)
    }

    @Test fun `box table inside fence is lifted too`() {
        val box = "```\nProvider │ Status\n─────────┼───────\ncopilot  │ OK\n```\nAfter."
        val t = parseTables(box)
        assertNotNull(t)
        assertEquals(listOf("Provider", "Status"), t!!.tables[0][0])
        assertEquals("After.", t.prose)
    }

    @Test fun `plus-junction ascii rules between rows do not become cells`() {
        val t = parseTables("+-----+-----+\n| H1  | H2  |\n+-----+-----+\n| a   | b   |\n+-----+-----+\n| c   | d   |")
        assertNotNull(t)
        val rows = t!!.tables.single()
        assertEquals(3, rows.size)  // header + 2 data rows, rules dropped
        assertEquals(listOf("H1", "H2"), rows[0])
        assertEquals(listOf("a", "b"), rows[1])
        assertEquals(listOf("c", "d"), rows[2])
        assertEquals("", t.prose)
    }

    @Test fun `prose line with a single dash cell survives`() {
        val t = parseTables("| A | B |\n|---|---|\n| - | x |")
        assertNotNull(t)
        assertEquals(2, t!!.tables[0].size)   // header + the dash-cell row
        assertEquals(listOf("-", "x"), t.tables[0][1])
    }

    @Test fun `horizontal rule in prose is not consumed`() {
        val md = "Before\n\n---\n\nAfter:\n| A | B |\n|---|---|\n| 1 | 2 |"
        val t = parseTables(md)
        assertNotNull(t)
        assertTrue(t!!.prose.contains("---"))
    }

    @Test fun `inline markdown styling is stripped from cells`() {
        val t = parseTables("| **Model** | `cost` |\n|---|---|\n| *GPT* | $1 |")
        assertEquals(listOf("Model", "cost"), t!!.tables[0][0])
        assertEquals(listOf("GPT", "$1"), t.tables[0][1])
    }

    @Test fun `html table parses with rowspans of tags stripped`() {
        val html = """
            Here is the report:
            <table>
              <tr><th>Model</th><th>Cost</th></tr>
              <tr><td><b>GPT</b></td><td>$1 &amp; up</td></tr>
              <tr><td>Gemini</td><td>free</td></tr>
            </table>
            End.
        """.trimIndent()
        val t = parseTables(html)
        assertNotNull(t)
        val rows = t!!.tables.single()
        assertEquals(listOf("Model", "Cost"), rows[0])
        assertEquals(listOf("GPT", "$1 & up"), rows[1])  // <b> stripped, entity decoded
        assertEquals(3, rows.size)
        assertEquals("Here is the report:\nEnd.", t.prose)
    }

    @Test fun `html table uppercase attributes and mixed markdown table coexist`() {
        val md = "<TABLE><TR><TD>a</TD><TD>b</TD></TR><TR><TD>1</TD><TD>2</TD></TR></TABLE>\n" +
            "| X | Y |\n|---|---|\n| 3 | 4 |"
        val t = parseTables(md)
        assertEquals(2, t!!.tables.size)
        assertEquals(listOf("a", "b"), t.tables[0][0])
        assertEquals(listOf("X", "Y"), t.tables[1][0])
    }

    @Test fun `html table inside code fence stays literal`() {
        assertNull(parseTables("```\n<table><tr><td>a</td><td>b</td></tr><tr><td>1</td><td>2</td></tr></table>\n```"))
    }

    @Test fun `html table needs two rows minimum`() {
        assertNull(parseTables("<table><tr><td>only</td></tr></table>"))
    }

    @Test fun `prose-only newline collapse keeps paragraphs`() {
        val t = parseTables("line1\n| A | B |\n|---|---|\n| 1 | 2 |\nline2")
        assertTrue(t!!.prose.contains("line1"))
        assertTrue(t.prose.contains("line2"))
    }
}
