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

    @Test fun `table inside a code fence stays literal text`() {
        assertNull(parseTables("Example:\n```\n| A | B |\n|---|---|\n| 1 | 2 |\n```\nDone."))
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
